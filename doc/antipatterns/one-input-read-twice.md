# One input read twice for two purposes

**Symptom:** two calls in one method each open or fetch the same input, a file, a config value
or a shared field, because each needs it for something else: one to build an object, one to
describe it, validate it or log it.

## Example

A loader builds a TLS context from a key and a certificate file. It also describes the
certificate (subject, serial, expiry) for the log, and records the files it read, so a watcher
can reload when they change:

```java
SslContext context = createSslContext(cfg, files);   // opens certificate and key
String description = describe(cfg, files);           // opens the certificate again
return new Loaded(context, description, files.snapshot());
```

## Why it hurts

- The certificate is read and parsed twice, for no gain.
- The file can change between the two reads. A certificate renewal at that moment gives a
  context built from the old certificate and a log line about the new one. The state reports
  something it does not use, exactly when the operator looks at the log to check the renewal.
- The same applies to every input that can change under the reader: files, an environment
  that is reloaded, a field written by another thread.

## Instead

Read the input once into a value and derive everything from that value:

```java
byte[] cert = readFully(files, cfg.certPath());
byte[] key = readFully(files, cfg.keyPath());
String description = describe(cfg.certPath(), cert);
SslContext context = createSslContext(cert, key);
```

The context and the description now come from the same bytes, whatever happens to the file.

## Related

- `AGENTS.md`: "Read concurrent state once per method invocation when multiple values must come
  from the same snapshot". The file system is concurrent state.
