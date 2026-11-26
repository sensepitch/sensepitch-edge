# A resource handed to a library without an owner

**Symptom:** a stream, channel or buffer is opened inline in the argument list of a library call
(`library.load(open(path))`). Nothing in the code says who closes it, and no try-with-resources
surrounds it.

## Example

A TLS context is built from a key and a certificate. The locations are files or resources inside
the jar:

```java
SslContextBuilder.forServer(files.open(cfg.certPath()), files.open(cfg.keyPath())).build();
```

For a file, `open` returns a stream over bytes already read, which needs no closing. For a jar
resource it is a stream on the jar file. Whether the library closes the streams it is given is
not visible here; at the time of the review nobody had checked.

## Why it hurts

- Either the library closes the streams or nobody does. The reader cannot tell which without
  reading the library source, and the answer can change with a library version.
- A leak stays invisible while the common case (a file read into memory) happens to need no
  close; it shows up only with the other kind of location, e.g. as open file handles after many
  reloads.
- For Netty buffers the same shape is worse: reference counts must be released by exactly one
  owner, see `AGENTS.md`.

## Instead

- Whoever opens a resource closes it, with try-with-resources in the same method.
- Hand the library a value when it only reads: read the bytes, pass a `ByteArrayInputStream` or
  the bytes themselves. Then there is nothing to own.

  ```java
  byte[] cert = readFully(files, cfg.certPath());   // opens and closes
  byte[] key = readFully(files, cfg.keyPath());
  SslContextBuilder.forServer(new ByteArrayInputStream(cert), new ByteArrayInputStream(key));
  ```

- If ownership really passes to the library, say so in a comment that names the library
  method documenting it.

## Related

- `AGENTS.md`: "Handle Netty's reference counted buffers correctly across ownership and async
  boundaries"; the same ownership question for every resource.
