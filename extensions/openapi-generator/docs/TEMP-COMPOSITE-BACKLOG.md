# Temporary Composite Schema Backlog

This document tracks the remaining work for composed schema support in the
Helidon OpenAPI generator. It is temporary and should be removed once the
feature is complete.

## Remaining Items

- [x] Add runtime JSON converter generation for `oneOf` and `anyOf` model types so
      generated request and response bodies can be serialized and deserialized.
- [x] Enforce composed-schema matching semantics at runtime:
      - `oneOf` must resolve to exactly one matching subtype
      - `anyOf` must resolve to at least one subtype and reject ambiguous matches
- [x] Expand tests from source-shape checks to runtime-oriented coverage for
      discriminator and structural matching behavior.
- [ ] Update permanent docs to describe the final runtime behavior and remove
      this temporary backlog file.
