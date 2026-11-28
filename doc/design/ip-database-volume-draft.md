# IP database on the pods (draft)

**Draft, nothing decided.** This is the research for `TODO.md` item 13 of "Kubernetes, direct
node ingress", "IP database on the pods". It covers how the IPinfo database that
[`IpInfoCountryAndAsnLookup`](../../src/main/java/org/sensepitch/edge/IpInfoCountryAndAsnLookup.java)
reads from `ipLookup.ipInfoPath` gets onto the edge pods. It also covers how a new database
reaches a running edge without a restart. The reload side is in
[`ConfigFileWatcher`](../../src/main/java/org/sensepitch/edge/ConfigFileWatcher.java),
[`ConfigFiles`](../../src/main/java/org/sensepitch/edge/ConfigFiles.java) and
[`CombinedIpTraitsLookup`](../../src/main/java/org/sensepitch/edge/CombinedIpTraitsLookup.java);
why it is built that way is in [config-files.md](config-files.md). The open choices are
unchecked boxes for the maintainer.

The code statements were checked against `main` at `e817c72` on 2026-11-23. The web sources
were read on 2026-11-23.

## Purpose and scope

The edge runs as a DaemonSet on the ingress nodes (item 9). Every edge pod needs a local copy of
the database. A new copy should be used without restarting the pod, because a restart refuses
connections on that node for a moment (item 9, "Restart").

Constraints from the project:
- the chart runs the edge with `readOnlyRootFilesystem: true`, as UID 65532, with an `emptyDir`
  only at `/tmp` ([values.yaml](../../charts/sensepitch-edge/values.yaml),
  [deployment.yaml](../../charts/sensepitch-edge/templates/deployment.yaml));
- the image is meant to be public on `ghcr.io` ([oci-image.md](oci-image.md));
- the edge reads files by path and has no download code. Adding download code only for this
  is not proposed.

Out of scope: the GeoIP2 databases (`ipLookup.geoIp2`), which are read the same way, and the
choice between IPinfo and other providers.

## Overview: facts

### The database (evidence)

- IPinfo Lite is the current free database. IPinfo calls it "the evolution of our previous free
  IP to Country ASN database" and says "We will continue to support these previous versions, and
  we do not require users to migrate"
  ([IPinfo Lite FAQ](https://community.ipinfo.io/t/ipinfo-lite-faq/6768), 2025-05-01).
- Size: the download page lists `ipinfo_lite.mmdb` at **23.71 MB**, "Last Updated: Nov 23, 2026"
  ([IPinfo Lite database](https://ipinfo.io/developers/ipinfo-lite-database)).
- Download: `curl -L https://ipinfo.io/data/ipinfo_lite.mmdb?token=$TOKEN -o ipinfo_lite.mmdb`
  (same page), so every download needs an account token.
- Update cadence: "Data updates every 24 hours" ([ipinfo.io/lite](https://ipinfo.io/lite)).
- License: "Creative Commons Attribution-ShareAlike 4.0 International License", crediting IPinfo
  as the source (both IPinfo pages above).
- Fields: Lite has `country_code` and `asn`, which `CountryCodeAndAsnTuple` reads (`asn` as
  `AS<number>`). The FAQ's predecessor, `country_asn.mmdb`, is not described on the pages read.
  Whether it uses the same field names was not checked, and neither file was tested.

### Kubernetes limits (evidence)

- "Individual Secrets are limited to 1MiB in size."
  ([Secrets](https://kubernetes.io/docs/concepts/configuration/secret/), last modified
  2026-07-23)
- "The data stored in a ConfigMap cannot exceed 1 MiB."
  ([ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/))
- So the database, about 24 times the limit, can be neither a Secret nor a ConfigMap. The chart
  README suggested `extraSecretMounts` for it; this is corrected in the same change as this draft.
- Image volumes: "Stable since Kubernetes v1.36; enabled by default", first available in v1.31.
  "The volume is resolved at Pod startup", "The volume gets re-resolved if the Pod gets deleted
  and recreated", and it is mounted read-only
  ([Volume types](https://kubernetes.io/docs/reference/storage/volume-types/), last modified
  2026-09-25). So a new database in an image volume needs a new pod.
- Native sidecar containers (init containers with `restartPolicy: Always`): "Stable since
  Kubernetes v1.33". They "remain running during the entire life of the Pod" and "share the same
  network and storage namespaces with the primary container"
  ([Sidecar containers](https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/)).
- `emptyDir`: "created when the Pod is assigned to a node", and "When a Pod is removed from a
  node for any reason, the data in the `emptyDir` is deleted permanently". It survives container
  crashes (Volume types page).
- `hostPath`: "presents many security risks. If you can avoid using a `hostPath` volume, you
  should". Admission restrictions are "only effective when you additionally require that any
  mounts of that `hostPath` volume are **read only**" (Volume types page).

### What the edge does on a changed file (evidence from the code and tests)

- The configuration records the database path with `ConfigFiles.record` before the reader opens
  it, so `Proxy#referencedFiles()` contains it (`ConfigReloadTest.rotatedIpDatabaseIsOpenedAgain`
  asserts this). `ConfigFileWatcher` watches the parent directory of each referenced file and
  compares the fingerprint (modification time, size, inode). A changed fingerprint reloads the
  unchanged configuration by itself. No admin call or configuration change is needed.
- An unchanged database keeps the same `IpTraitsLookup`. A rotated one is opened again (same
  test, with an atomic `Files.move`).
- While a configuration is active, the watcher ignores a missing file ("it may be in the middle
  of a replacement"), so the active lookup stays.
- At startup a missing file is an error: `ConfigFiles.record` throws `NoSuchFileException`.
- The reader is memory mapped: `new Reader(File)` in maxmind-db 3.2.0 uses
  `FileMode.MEMORY_MAPPED` (checked with `javap`). The JDK says of mapped buffers: "All or part
  of a mapped byte buffer may become inaccessible at any time, for example if the mapped file is
  truncated". It recommends to "avoid the manipulation of a mapped file by this program, or by a
  concurrently running program, except to read or write the file's content"
  (`java.nio.MappedByteBuffer` API doc, JDK 25 `src.zip`).
- A truncated database is rejected when it is opened. Checked on 2026-11-23 with maxmind-db
  3.2.0 and the 231-byte test database of `TinyMmdb`, cut to 57, 115, 211 and 230 bytes. Each
  `new Reader(File)` threw `java.lang.IllegalArgumentException: newLimit > capacity`, and the
  full file opened. That is a `RuntimeException`, not the library's `InvalidDatabaseException`.
  `Proxy.reload` catches `RuntimeException`, keeps the active configuration and keeps watching
  the file, so a write that completes later retries. Not checked with a real 24 MB file cut at
  other points.
- The old `Reader` is closed `EdgeState.REPLACED_IP_LOOKUP_CLOSE_DELAY_MILLIS` (5 s) after the
  reload that replaced it, so connections accepted just before the swap still get their traits
  (`EdgeState#closeReplacedBy`). Whether the close frees the mapping, and the disk space of a
  replaced file, before the buffer is garbage collected is not checked.

### Projected Secret and ConfigMap volumes (evidence, relevant only below 1 MiB)

The kubelet's atomic writer keeps the files in a timestamped directory and links `..data` to it.
On an update it creates a `..data_tmp` link and renames it to `..data`: "rename is atomic". The
visible file is a link `<file> -> ..data/<file>`
([atomic_writer.go](https://raw.githubusercontent.com/kubernetes/kubernetes/master/pkg/volume/util/atomic_writer.go),
`master` on 2026-11-23). The update arrives after "the kubelet sync period + cache propagation
delay", and a `subPath` mount "does not receive automated Secret updates" (Secrets page). The
watcher handles the swap: the rename is an event in the watched directory, and the fingerprint
follows the link to a new inode (`ConfigFileWatcherTest` and `ConfigFilesTest` build this
layout). The database is too large for this path. It still applies to the token Secret that a
downloader needs.

## Options

Every update path must follow this rule (evidence above): write the new database to a temporary
file **in the same directory** and rename it over the old one. Never write in place, for example
with `curl -o` onto the live path or `cp` over it. The active reader maps the same inode, and a
truncation can make lookups fail.

### Where the file comes from on the pod

- [x] **A. Init container plus sidecar into an `emptyDir`.** Chosen, see "Decision". An init
  container downloads the database before the edge starts. A native sidecar (`restartPolicy:
  Always`) downloads it again, e.g. daily, and renames it into place. The token is a small Secret
  mounted into these two containers only.
  - For: works on any cluster from v1.33 and needs no node access. The pod is self-contained,
    and the update reaches the edge through the existing watcher.
  - Against: every pod downloads 24 MB per update and needs egress to `ipinfo.io`. A pod cannot
    start while IPinfo is unreachable, because the edge refuses to start without the file. A
    downloader image has to be chosen and pinned, and the chart needs a values block for it.
- [ ] **B. `hostPath` directory on the ingress nodes, filled by a host updater.** A systemd
  timer (or cron) on each node downloads the file and renames it into place. The chart mounts
  the directory read-only.
  - For: fits the DaemonSet on dedicated ingress nodes and the host-level setup in the
    section's introduction. The file survives pod restarts, so a new pod starts without a
    download, and the pod needs no egress.
  - Against: `hostPath` is the volume type Kubernetes warns about; a read-only mount reduces the
    risk. The node setup is outside the chart and has to be documented and kept the same on
    every node.
  - Mount the directory, not the file. Assumption, not verified: a bind mount of a single file
    keeps the inode it saw at pod start, so a rename on the host would not reach the pod. Check
    this on the cluster before relying on it.
- [ ] **C. Image volume with the database as an OCI artifact.** CI pushes a small image with
  the file daily, and the pod mounts it read-only.
  - For: no download code on the nodes or in the pod, and each version is an image with a
    digest.
  - Against: the volume is resolved only at pod start, so every update is a pod restart, which
    item 9 wants to avoid. It needs Kubernetes v1.36 (or v1.35 with the beta default) and a
    runtime that supports it. Publishing the artifact publicly redistributes the data, see D.
- [ ] **D. Baked into the edge image.**
  - For: the simplest pod spec.
  - Against: every update is an image build and a rollout restart, and the image grows by
    24 MB. A public image redistributes the data under CC BY-SA 4.0 and has to carry its
    attribution and share-alike terms. Assumption: the license allows this with these terms; it
    was not reviewed legally.
- Rejected: **Secret or ConfigMap**, because of the 1 MiB limit (evidence above).
- Not researched further: a `ReadOnlyMany` PersistentVolume shared by all nodes. This assumes
  plain cloud hosts without a shared storage class, which was not verified for the target
  provider.

### How a new database reaches a running edge

The path follows from the volume: A and B reach the running edge through the watcher, nothing
to build in the edge; C and D need a rollout restart, with the refused connections of item 9 on
each node. The interval is open:

- [ ] daily, matching IPinfo's cadence
- [ ] less often (e.g. weekly) to save transfer; the IPinfo pages read do not require an
  interval

### Follow-up work any choice needs

- a chart values block for the chosen volume (today the chart can only add Secrets), and its
  part in the operator documentation (item 15);
- a test that the running watcher picks up a database renamed into its directory, without a
  `reload` call. `ConfigReloadTest` calls `reload` itself.

## Decision

Decided by the maintainer on 2026-11-28, recorded in `TODO.md` item 13: **A**, the init container
plus the native sidecar into a shared `emptyDir`. The edge mounts the volume read-only; the
downloaders write a temporary file in the same directory and rename it into place, and the
existing watcher reloads. The token is mounted only into the downloader containers, so the token
and the egress to `ipinfo.io` stay out of the public-facing process, and the edge needs no
download code.

Considered first and dropped: the edge downloads the database itself into a writable volume. Its
advantage, one mechanism for Kubernetes and standalone setups, is covered by a host timer that
writes the same way; against it, the edge would hold the token, need egress, and carry an HTTP
client with validator handling, retries and the rename.

## Recommendation (superseded by the decision)

B, if the ingress nodes get host-level setup anyway (items 9 and 12); otherwise A. Both keep
updates restart-free through the watcher, which already works. C and D trade the restart-free
update for a simpler pod, against the restart goal of item 9.

## Limitations

- No real IPinfo file was downloaded: the research shell has no network, and a download needs a
  token. The size and fields come from IPinfo's pages.
- Nothing was tried on a cluster. The `hostPath` file-versus-directory mount behaviour and the
  image volume support of the target runtime are unverified.
