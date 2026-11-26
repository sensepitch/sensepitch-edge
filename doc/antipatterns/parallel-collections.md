# Parallel collections for one concept

**Symptom:** two collections that describe the same things from two sides and are filled
together. Two forms:
- two lists with related elements: `collectors` and `closeables`, `names` and `values`.
  Constructors multiply, because some callers have both and some only one.
- two maps with the same keys, each holding one field of the same value: `contextsByHost` and
  `descriptionsByHost`, filled in the same loop.

## Example

An IP lookup queries several databases and closes their readers when a reload replaces it. Some
databases are queried through small lambdas around a reader, and a lambda cannot be closed. So
the lookup kept two lists:

```java
CombinedLookup(List<Collector> collectors, List<AutoCloseable> closeables, Metrics metrics) {...}

// opening the configured databases
AsnReader asn = new AsnReader(path);
collectors.add(asnTraits(asn));   // a lambda around the reader
closeables.add(asn);              // the same reader, again
```

Around it grew:
- four constructors (from config, with and without closeables, from a record carrying both
  lists)
- a private record to return the two lists from one method
- two adapter methods and two interfaces only to turn the readers into lambdas

### The same keys in two maps

A TLS setup loads, per host, a bundle of the TLS context, the description of the certificate for
the log, and the files it was read from. It then split each bundle into two maps:

```java
for (SniConfig sni : cfg.snis()) {
  CertificateBundle bundle = loader.bundleFor(sni.ssl());
  contextsByHost.put(sni.host(), bundle.context());           // for the SNI selection
  descriptionsByHost.put(sni.host(), bundle.description());   // for the log
}
```

The bundle already holds both. The second map was kept as a field, only for the log line at
startup and after a reload.

## Why it hurts

- The relation between the lists is implicit. Nothing prevents a reader that is queried but not
  closed, or closed but never queried.
- Every caller has to supply both, so tests pass empty lists or get their own constructor.
- The amount of code is driven by the representation, not by the problem.
- With maps, every new use (another log line, a metric per host) is tempted to add a third map
  with the same keys, and the key sets can drift apart.

## Instead

- One element type that carries both roles. Here, the collector interface extends
  `AutoCloseable` with a no-op default; each database reader implements it directly, and a
  lambda without resources keeps the default.
- One list, one constructor, one loop for querying and one for closing.
- If two values really belong together but have different types, put them in one record per
  element, not in two collections.
- One map from the key to the whole value, `bundlesByHost`. Derive a projection where it is
  needed, e.g. host to description when a log line is written, once per reload:

  ```java
  bundlesByHost.forEach((host, bundle) -> descriptionsByHost.put(host, bundle.description()));
  ```

## Related

- `AGENTS.md`: "Write no code that is not needed".
