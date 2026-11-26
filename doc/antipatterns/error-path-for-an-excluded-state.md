# Error handling for a state an earlier step already excludes

**Symptom:** a later step catches an error that an earlier step on the same input would already
have thrown, and turns it into a fallback value. Two steps handle one input differently: one
fails, the other degrades.

## Example

A loader builds a TLS context from a certificate file, then describes the certificate for the
log:

```java
SslContext context = createSslContext(cfg, files);   // throws if the file cannot be read
String description = describe(cfg, files);

static String describe(SslConfig cfg, ConfigFiles files) {
  try (InputStream in = files.open(cfg.certPath())) {
    return format(parseCertificate(in));
  } catch (IOException | CertificateException e) {
    return "unreadable: " + e;                       // degrades instead of failing
  }
}
```

The context was built from the same file, so `describe` can fail only if the file changed
between the two reads (see [one input read twice](one-input-read-twice.md)).

## Why it hurts

- One input, two failure behaviours: the context build rejects the file, the description
  accepts it as `"unreadable"`.
- The fallback hides the race it exists for. Instead of failing the load of an inconsistent
  certificate, the state goes live with a log line that looks like data.
- The branch shows up as uncovered, or covered only by a test of the helper alone, which proves
  that the helper degrades, not that degrading is right.

## Instead

- Validate once, at the first use, and let the later steps work on the validated value.
- Fail rather than degrade when the state is inconsistent.
- With the input read once, the description comes first and rejects an invalid certificate
  with a clear message; the context is built from the same bytes:

  ```java
  String description = describe(cfg.certPath(), cert);   // throws IllegalArgumentException
  SslContext context = createSslContext(cert, key);
  ```

## Related

- `AGENTS.md`, coverage rule: "A gap is a design question: remove error handling for a case
  that cannot happen, validate earlier, or test the case".
