# The callee does the caller's bookkeeping

**Symptom:** a helper writes into state its caller owns, a field of the caller or a collector
passed down, on one path, while the caller does the same bookkeeping itself on the other paths.

## Example

A build of the TLS setup finds a context per certificate configuration: one already built in
this build, one taken over from the previous build if its files are unchanged, or a new one. On
every path, the files of the context must be recorded in the build's list of dependencies:

```java
Loaded load(SslConfig cfg) {
  Loaded context = previous.get(cfg);
  if (context == null || !context.files().unchanged()) {
    context = loadNew(cfg);                 // records the files itself, in a finally
  }
  dependencies.recordAll(context.files());  // records them (again)
  return context;
}
```

Whether each path records exactly once can only be seen by reading both methods.

## Why it hurts

- The responsibility is split by path. A new path, e.g. a cache hit inside the build, is easily
  missed or recorded twice, and a test does not notice when the recording is idempotent.
- The helper needs the caller's collector, which ties it to the caller: it cannot be static, and
  it cannot be reused or tested without that state.
- The helper's failure handling (record also on failure) is a decision about the caller's data,
  made in the wrong place.

## Instead

- The helper works on what it is given and returns what it did; the caller decides, in one
  place, what happens on every path. Here the helper reads through a collector that belongs to
  the caller's build and forwards each file at the moment it is read, so the helper records
  nothing itself and the caller has nothing to merge:

  ```java
  Loaded contextFor(SslConfig cfg) {
    Loaded context = previous.get(cfg);
    if (context != null && context.files().unchanged()) {
      dependencies.recordAll(context.files());              // taken over, not read
    } else {
      context = loadContext(cfg, dependencies.component()); // reads, recorded as it reads
    }
    return context;
  }
  ```

- Each branch records once, visibly, also when loading fails.

## Related

- [`finally` doing the work of the normal path](finally-for-the-normal-path.md): one owner per
  side effect. That one is about the duplicated effect, this one about where the owner sits.
- [A name that hides a second duty](name-hides-a-second-duty.md), the naming side of the same
  example.
