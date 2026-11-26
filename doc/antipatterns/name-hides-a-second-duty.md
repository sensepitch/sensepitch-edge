# A name that hides a second duty

**Symptom:** a method named for one action, `load`, `parse`, `get`, also performs a second one
whose effect lies outside its result: recording, caching, logging, registering. Its doc comment
has to explain what the name does not say.

## Example

A loader builds a TLS context from a key and a certificate file. The files it reads must also be
recorded in the list of files the current configuration depends on, so a watcher can reload when
they change:

```java
Loaded loadNew(SslConfig cfg) {
  ConfigFiles own = new ConfigFiles();
  try {
    return new Loaded(createSslContext(cfg, own), own.snapshot());
  } finally {
    dependencies.recordAll(own.snapshot());   // a second duty, not in the name
  }
}
```

A reader of the caller sees `loadNew(cfg)` and expects a new context, not a change to the list
of dependencies.

## Why it hurts

- The call site does not show the effect. Reviewing the caller, one misses that the dependency
  list changes here.
- Every other caller gets the effect unasked, e.g. a test or a validation pass that only wants
  to know whether the files load.
- The doc comment carries what the name hides, and goes stale first when the code changes.

## Instead

- One duty per method, named for it. A method that returns a value has no further effect.
- If both are needed, the caller combines them, where both are visible:

  ```java
  ConfigFiles read = new ConfigFiles();
  try {
    context = loadContext(cfg, read);       // only loads, `read` collects what it read
  } finally {
    dependencies.recordAll(read.snapshot());
  }
  ```

- If the two duties really cannot be separated, the name says both (`loadAndRecord`), which
  usually shows that they should be separated after all.

## Related

- `AGENTS.md`: "Every helper's doc says what it is for, not only what it does".
- [The callee does the caller's bookkeeping](callee-does-the-callers-bookkeeping.md), the
  structural side of the same example.
