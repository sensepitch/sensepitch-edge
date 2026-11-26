# Files referenced by the configuration

Covers how the edge reads and watches the files a configuration points to: TLS keys and
certificates, secrets, response and fallback pages, IP databases.
[`ConfigFiles`](../../src/main/java/org/sensepitch/edge/ConfigFiles.java) reads and records them,
[`SecretConfig`](../../src/main/java/org/sensepitch/edge/SecretConfig.java) is the configuration
shape of a secret, and
[`ConfigFileWatcher`](../../src/main/java/org/sensepitch/edge/ConfigFileWatcher.java) reloads when
one of them changes. The behaviour is in the class docs; this document records why it is built
this way.

## Purpose and scope

On Kubernetes, certificates (cert-manager), admission secrets and log server credentials come
from mounted Secrets that change without the configuration file changing. A reload has to pick
them up, and secrets must not be in clear text in a ConfigMap or in the diagnostic log.

Constraints:
- the watcher must not need a second description of the configuration, a new file-valued key
  must be watched without touching the watcher;
- a component kept over a reload because its configuration is unchanged (IP lookup, request log
  destination) must still be rebuilt when its file changed;
- secrets are read in one place, with one configuration shape.

## Design decisions

### Close the watcher before stopping the proxy

File events and admin reloads share the reload lock. Closing under that lock lets the current
callback finish and prevents a queued event or admin request from calling into the proxy during
shutdown. Closing the watch service wakes the worker. The application waits for the worker
outside the lock because a worker processing an event may still need to acquire it.

### Record what was read, not what is configured

The first version had a class listing every file-valued key of `ProxyConfig`. It repeated the
Let's Encrypt host enumeration of `Proxy`, missed the response and fallback pages from the start,
and would drift with every new key. Now each build of the configuration gets one `ConfigFiles`,
every read of a referenced file goes through it, and a file that a library opens itself (the
MaxMind readers) is announced with `record`. The set of watched files is by construction the set
the active configuration used. `Proxy#referencedFiles()` publishes it after each applied build,
and the watcher asks for it, so the reload action does not have to call back into the watcher.

### Fingerprint, not content hash

A file is recorded with last modification time, size and file key (the inode on Linux), taken
before it is read. Hashing the content would read an IP database of tens of megabytes on every
directory event. The file key changes on the `..data` symlink swap of ConfigMap and Secret
volumes and on an editor's rename, the time on an in-place write. Taking it before the read means
a change during the read shows up as a difference later. Not verified: a file system with a
coarse time stamp and no file key could miss an in-place rewrite of the same size within one
tick. The configuration file itself keeps its content hash, which the `#END` check needs anyway.

### Watch the files of a rejected configuration too

`ConfigFiles` records a file before it reads it, a missing one as `Fingerprint.ABSENT`. When a
reload is rejected, `Proxy#referencedFiles()` reports the files the rejected configuration tried
to read next to the active ones. So a cert-manager renewal that fails halfway, or a secret file
created after the configuration that needs it, is retried when the file is fixed, without
touching the configuration file. The candidate files are dropped with the next applied reload.

### Known state advances on every attempt

As for the configuration content, the fingerprints of the referenced files count as known once a
reload was tried, not only once it was applied. A renewal that writes the key before the
certificate gives a rejected reload (each newly loaded TLS context must pass a handshake in
memory, see [`SslContextCheck`](../../src/main/java/org/sensepitch/edge/SslContextCheck.java)),
and the next write triggers the retry. Advancing only on success would retry on every unrelated
event in a watched directory while a file stays broken.
After an applied reload the watcher replaces its set with the files of the new build.

### Reuse needs equal configuration and unchanged files

`EdgeState` keeps the IP lookup and `ReloadableRequestLogger` keeps a destination when the
configuration is equal. Both keep the fingerprints of the files they read in a `ConfigFiles` of
their own and are taken over only if `ConfigFiles.unchanged` holds. Otherwise a renewed database
or a rotated token would trigger a reload that keeps the old instance.

### One secret record, the plain string form dropped

`SecretConfig` holds either `value` or `file`, does no I/O, and masks the value in `toString` and
in the YAML dump (`Proxy.toYaml`). Alternatives:
- a `secret`/`secretFile` pair on every record with a secret: repeats the validation and the
  reading per component;
- a generic `file:` prefix on any string resolved by the loader: implicit, every string key
  becomes a possible file reference;
- keeping `secret: munich` as shorthand: needs scalar-to-record support in both loaders
  (`RecordConstructor`, `EnvInjector`) for one key.

The break is reported with the line, not the value: `RecordConstructor` names the record type and
the line when it finds a scalar.

### Hash the masked configuration for the request log

Including resolved secret digests let a reader with the configuration check candidate secrets
against a logged id. `EdgeState.configId` now hashes only the masked YAML from `Proxy.toYaml`.
A configured file path contributes to the id. File fingerprints independently trigger reloads,
so replacing credentials at the same path applies the new values while preserving the id.

### Secret headers as a list

The OTLP credentials are `secretHeaders`, a list of `{name, secret}`, next to the plain `headers`
map. A `Map<String, SecretConfig>` would need a key inside the value for `EnvInjector`, and
turning `headers` into secrets would force `{value: ...}` on harmless headers like
`VL-Stream-Fields`. The secret headers are merged into the plain ones before the destination is
built, so `OtlpLogDestination` is unchanged.

## Limitations

- A file of a rejected configuration is watched only if its directory exists when the reload is
  tried; a directory created later is registered on the next reload attempt.
- A file of the active configuration that disappears does not trigger a reload, it may be in the
  middle of a replacement.
- Secret files follow a fixed convention (one final `\n` or `\r\n` removed, blank rejected);
  there is no option for files that need other trimming.
