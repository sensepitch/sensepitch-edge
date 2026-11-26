# A value named for how it came about

**Symptom:** a type or variable is named with a participle or a step of the process that
produced it: `Loaded`, `Parsed`, `Fetched`, `Computed`, `Built`, `Result`. To know what it is,
one has to read its fields.

## Example

A TLS setup keeps, per certificate configuration, the TLS context built from it, the
description of the certificate for the log, and the files that were read. The next build takes
over an entry whose files are unchanged:

```java
private record Loaded(SslContext context, String description, Snapshot files) {}

private final Map<SslConfig, Loaded> loaded = new HashMap<>();

Loaded contextFor(SslConfig cfg) {
  Loaded context = loaded.get(cfg);
  ...
  contexts.put(host, context.context());
```

## Why it hurts

- The name describes one path and is wrong on the others. An entry taken over unchanged from
  the previous build was not loaded in this build; one shared within the build was loaded once
  for several hosts.
- It says nothing about the content. `Loaded context` suggests an `SslContext`, while the
  variable holds a record that contains one, so `context.context()` appears.
- Names derived from it spread through the code: `loaded`, `loadNew`, `contextFor`, each a
  little more misleading than the last.

## Instead

- Name the thing the value is, as a noun from the domain: `CertificateBundle`, a certificate
  with its key and what was built from them.
- Let variables, maps and methods follow the noun:

  ```java
  private record CertificateBundle(SslContext context, String description, Snapshot files) {}

  private final Map<SslConfig, CertificateBundle> bundlesByConfig = new HashMap<>();

  CertificateBundle bundleFor(SslConfig cfg) {
    CertificateBundle bundle = bundlesByConfig.get(cfg);
    ...
    contexts.put(host, bundle.context());
  ```

- A participle fits a method or a test that describes an action, e.g.
  `changedFileIsLoadedAgain`, not a value.

## Related

- `AGENTS.md`: "Keep class and method names descriptive and consistent with existing
  `*Config`, `*Handler`, and `*Lookup` patterns".
- [A name that hides a second duty](name-hides-a-second-duty.md), the method side of naming.
