# A special entry under a magic key

**Symptom:** a map of domain keys, host names or user ids, also holds one entry of another kind
under a constant such as `"default"`, `"*"`, `""` or `-1`.

## Example

A TLS setup selects a certificate by the SNI host name a client sends, and falls back to a
default certificate when no host matches. For the log, it kept the certificate descriptions by
host, and the default among them:

```java
static final String DEFAULT_CERTIFICATE = "default";

descriptionsByHost.put(sni.host(), bundle.description());
...
descriptionsByHost.put(DEFAULT_CERTIFICATE, defaultBundle.description());
```

## Why it hurts

- `"default"` is also a valid single-label host name, so a host and the fallback can collide.
- Every consumer of the map has to know about the special key. Building the SNI selection from
  this map would register `"default"` as a host name.
- The different meaning of the entry, the fallback and not a host, is visible only at the
  constant. A reader of the map's type sees host names.

## Instead

- A field or a variable of its own for the special entry, `defaultBundle`. The map holds only
  domain keys:

  ```java
  Map<String, CertificateBundle> bundlesByHost;   // SNI host or wildcard pattern only
  CertificateBundle defaultBundle;                // the configured default, null if none
  ```

- Add the special entry only where an output format needs it, e.g. the default line of the
  certificate log, built from both fields when the line is written.
- If the special entry has to share the map, e.g. for an output format, use a key the domain
  cannot produce: a pseudo host with an underscore, which is not legal in a host name,
  `default_host`, never `default`.

## Related

- [A map named without its key](map-named-without-its-key.md): the doc of a map names its keys;
  a special key is a sign that the map holds two things.
- [Parallel collections for one concept](parallel-collections.md), the same refactoring.
