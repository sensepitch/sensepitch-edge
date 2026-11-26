# Tests asserting the identity of incidental instances

**Symptom:** a test uses `isSameAs` / `isNotSameAs` to tell results apart, although identity is
not the behaviour under test. Several cases of the test share the same input data, so only the
object identity distinguishes them.

## Example

A test of the SNI selection configures a default certificate, a wildcard entry and an exact
host entry, all with the same certificate configuration. To prove which entry a host name
selects, it compares the returned TLS contexts by identity:

```java
var setup = build(listen(ssl)
    .snis(List.of(new SniConfig("*.example.org", ssl), new SniConfig("api.example.org", ssl))));
assertThat(setup.map("one.example.org")).isNotSameAs(setup.map(null));
assertThat(setup.map("api.example.org")).isNotSameAs(setup.map("one.example.org"));
```

This worked while every entry built its own context. When equal configurations started to share
one context, which saves about 15 ms per context, the test failed, although the selection was
still right.

## Why it hurts

- The test pins an implementation detail (one instance per entry) instead of the behaviour (the
  entry chosen for a host name). A legitimate optimization breaks it.
- A fix that only flips the assertions would make the test pass without checking the selection
  any more.

## Instead

- Make the cases distinguishable by their data: a different certificate per entry. The
  identity then follows from the data, not from how many objects the code happens to build.
- Assert identity only where sharing or not sharing is the behaviour under test, e.g. "an equal
  configuration shares its context" or "an unchanged context is taken over by a reload", with
  a test named for it.
- Better still, assert on an observable property of the result, e.g. the certificate subject,
  where the API offers one.

## Related

- `AGENTS.md`, Testing Guidelines: test the behaviour in the class that owns the rule.
