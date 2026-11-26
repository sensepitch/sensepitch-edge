# One step split between caller and callee

**Symptom:** the same call on the same object appears in a method and in its caller, e.g.
`read.snapshot()`. Or the caller hands the callee an object only to finish, afterwards, a step
the callee started on it.

## Example

A loader builds a TLS bundle from a key and a certificate file. The bundle keeps a snapshot of
the files it was read from, so the next build can take it over if they are unchanged; taking
that snapshot is part of loading. The build also needs the files, to watch them, even when
loading fails:

```java
static Loaded loadBundle(SslConfig cfg, ConfigFiles read) {
  ...
  return new Loaded(context, description, read.snapshot());   // the snapshot, for the bundle
}

ConfigFiles read = new ConfigFiles();
try {
  bundle = loadBundle(cfg, read);
} finally {
  files.recordAll(read.snapshot());                           // the same snapshot, again
}
```

## Why it hurts

- The separation is violated. The caller has to know that the callee collects into `read`, and
  finishes the callee's step for the failure path.
- Two snapshots of one state can differ if something happens in between. Here nothing does, but
  nothing in the code guarantees it.
- The two halves drift apart. Two other callers with the same shape kept the merge after the
  call but lost the failure case: a missing file was never recorded, and a rejected reload was
  not retried when the file appeared.

## Instead

- One side owns the step. Here the collector forwards each file to the build at the moment it is
  read, so the loader takes its snapshot once and the caller takes none:

  ```java
  bundle = loadBundle(cfg, files.component());
  ```

- If the caller needs the result of the step, the callee returns it (`bundle.files()`); the
  caller does not repeat the step on the callee's object.
- If several callers repeat the same second half, the missing piece belongs in the API, here a
  collector for one component of the build.

## Related

- [The callee does the caller's bookkeeping](callee-does-the-callers-bookkeeping.md), the
  mirror image: there the callee does the caller's part, here the caller does the callee's.
- [`finally` doing the work of the normal path](finally-for-the-normal-path.md).
