# A map named without its key

**Symptom:** a map field or variable is named as a bare plural of a domain word,
`certificates`, `users`, `contexts`, often next to other maps in the same class or method.

## Example

A TLS setup builds, in one method, three maps: one from host name to the TLS context, one from
host name to a description of the certificate for the log (with a special `"default"` key for
the fallback certificate), and one from certificate configuration to everything built from it:

```java
Map<String, String> certificates = new HashMap<>();
var contexts = new LinkedHashMap<String, SslContext>();
...
contexts.put(sni.host(), bundle.context());
certificates.put(sni.host(), bundle.description());
...
certificates.put(DEFAULT_CERTIFICATE, bundle.description());
```

## Why it hurts

- The name claims certificates, the values are log strings.
- The key, a host name or the special `"default"`, is visible only at the `put` calls. A reader
  of `certificates.get(x)` elsewhere has to look up what `x` must be.
- With several maps side by side, a reader cannot tell which one to look in for what. With
  `String` keys, a lookup with the wrong kind of key compiles and returns `null`.

## Instead

- `<pluralValues>By<Key>` for an index, a collection looked up by a key: `descriptionsByHost`,
  `contextsByHost`, `bundlesByConfig`.
- `<key>To<Value>` for a translation or an association, one value mapped to another:
  `countryToContinent`.
- A domain noun where the mapping is self-explanatory, e.g. `headers` for HTTP headers.
- No suffix such as `Map`, `HashMap` or `ConcurrentMap`, unless the data structure itself is
  relevant.
- The doc comment names special keys, e.g. "`DEFAULT_CERTIFICATE` for the default".

## Related

- `AGENTS.md`, the rule "Name maps for their key and values".
- [A value named for how it came about](value-named-for-its-origin.md), the same question for a
  value type.
