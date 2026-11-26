# `finally` doing the work of the normal path, the same side effect in two places

**Symptom:** a `finally` block performs a side effect that is part of the normal result, not
cleanup, and the caller performs the same side effect again. Or a comment explains the failure
case, while the code treats success and failure alike.

## Example

A loader builds a TLS context from a key and a certificate file and records the files it read,
so a watcher can reload when they change. A file that failed to read must be recorded too, so
the reload is retried when it appears:

```java
Loaded loadNew(SslConfig cfg) {
  ConfigFiles own = new ConfigFiles();
  try {
    return new Loaded(createSslContext(cfg, own), describe(cfg, own), own.snapshot());
  } finally {
    files.recordAll(own.snapshot());      // on failure, and also on success
  }
}

Loaded load(SslConfig cfg) {
  Loaded context = loadNew(cfg);
  files.recordAll(context.files());       // the same snapshot again
  return context;
}
```

## Why it hurts

- The comment speaks of the failure case, the code does not tell the cases apart. A reader has
  to work out that the success path records twice.
- The duplicate is harmless only because `recordAll` ignores what it already has. Nothing says
  which place is responsible. A later change to one of them, e.g. recording a different
  snapshot, silently diverges from the other.
- The `finally` covers more than the step that can leave the files half recorded: building the
  context and describing the certificate run inside it, although they read nothing.

## Instead

- One owner per side effect. Here, whoever reads the files records them; the caller records
  only what it took over without reading.
- `finally` for cleanup, or for a step that must happen on every path, and only around the code
  that can fail before that step:

  ```java
  try {
    cert = readFully(own, cfg.certPath());
    key = readFully(own, cfg.keyPath());
  } finally {
    files.recordAll(own.snapshot());      // every file read or failed, exactly once
  }
  ```

- Remove a duplicate, do not tolerate it because it happens to be idempotent.

## Related

- `AGENTS.md`: "Write no code that is not needed".
