# Antipatterns

Patterns found in reviews of this code base that look reasonable but cause defects. Each file
names one, with a self-contained example and the shape that avoids it. The examples show the
code at the time of the review; they do not link to the sources, which have been fixed since.

Each file has the same sections: the symptom to spot it in a diff, the example, why it hurts,
what to do instead, and the related rules in [AGENTS.md](../../AGENTS.md).

- [One input read twice for two purposes](one-input-read-twice.md): derive everything from one
  read, or the results disagree when the input changes in between.
- [`finally` doing the work of the normal path](finally-for-the-normal-path.md): one owner per
  side effect, `finally` only around the step that can fail.
- [Error handling for an excluded state](error-path-for-an-excluded-state.md): a state an
  earlier step already excludes needs no fallback; validate once at the first use, fail rather
  than degrade.
- [Exception wrapped without meaning](exception-wrapped-without-meaning.md): wrap once at the
  boundary, with what failed and where, in the type the caller handles.
- [A resource handed to a library without an owner](resource-without-owner.md): whoever opens
  closes; hand the library a value when it only reads.
- [Tests asserting the identity of incidental instances](identity-of-incidental-instances.md):
  tell cases apart by their data, assert identity only where sharing is the behaviour.
- [Parallel collections for one concept](parallel-collections.md): two lists or two maps with
  the same keys; one element type with both roles, one collection.
- [A name that hides a second duty](name-hides-a-second-duty.md): one duty per method, named
  for it; the caller combines.
- [The callee does the caller's bookkeeping](callee-does-the-callers-bookkeeping.md): the helper
  returns what it did, the caller records on every path in one place.
- [A value named for how it came about](value-named-for-its-origin.md): name a value for what it
  is, a noun from the domain, and let variables and methods follow.
- [A map named without its key](map-named-without-its-key.md): `<pluralValues>By<Key>` for an
  index, `<key>To<Value>` for a translation, no bare plural.
- [A special entry under a magic key](special-entry-under-a-magic-key.md): a field of its own
  for the special entry, the map holds only domain keys.
- [One step split between caller and callee](step-split-between-caller-and-callee.md): one side
  owns the step; the callee returns its result, the caller does not repeat it.
