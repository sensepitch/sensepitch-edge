# Exception wrapped without meaning

**Symptom:** `throw new RuntimeException(e)`, or a new exception with a generic message like
`"IO error"`, right where a checked exception is caught. Different types for the same kind of
failure in neighbouring code.

## Example

A TLS context is built from a configured key and certificate file. Both failures are caused by
the configuration, a file that is missing or not valid:

```java
try {
  return SslContextBuilder.forServer(open(cfg.certPath()), open(cfg.keyPath())).build();
} catch (SSLException e) {
  throw new RuntimeException(e);
} catch (IOException e) {
  throw new IllegalArgumentException("IO error", e);
}
```

## Why it hurts

- The message names neither the file nor the step. A rejected reload logs "IO error", and the
  operator has to read the cause chain to find out that the key file of one SNI host is missing.
- The type is arbitrary. The caller rejects a configuration on `IllegalArgumentException`; an
  invalid certificate arrives as a plain `RuntimeException` and is handled as an unexpected
  failure, although it is the same kind of problem as the missing file.
- `RuntimeException(e)` only satisfies the compiler. It adds a stack frame and nothing else.

## Instead

- Wrap once, at the boundary where the meaning is known, with what failed and where:
  `new IllegalArgumentException("Cannot build the TLS context from " + cfg.certPath() + " and "
  + cfg.keyPath(), e)`.
- Choose the type the caller handles: a problem with the configuration or its files is a
  rejected configuration, here `IllegalArgumentException`, whatever the library threw.
- Where no meaning can be added, let the exception through (declare it) instead of wrapping it.

## Related

- `AGENTS.md`: "Treat network input as untrusted. Keep validation..."; configuration files are
  validated input too, and a rejected one must say why.
