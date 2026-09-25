# Task 1.2: Add Streaming Content Hashing

**Status:** Done
**Source:** [PLAN-001](../../../implementation-plans/PLAN-001-core-library.md)  
**ADR:** [ADR-001](../../../adrs/ADR-001-content-identity-and-core-boundaries.md)

## Goal

Add SHA-256 hashing that reads the exact uploaded bytes from a stream.

## Files

- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/hash/ContentHasher.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/hash/ContentHash.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/hash/Sha256ContentHasher.java`
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/hash/Sha256ContentHasherTest.java`

## Steps

- [x] Write `Sha256ContentHasherTest.hashesExactBytes` with a known SHA-256 digest.
- [x] Add `ContentHash` with `algorithm`, `hash`, and `sizeBytes`.
- [x] Implement `Sha256ContentHasher` using `MessageDigest` and streaming reads.
- [x] Run `./mvnw -pl dedup4j-core -Dtest=Sha256ContentHasherTest test`.

## Acceptance

- [x] Hash output is lowercase hex.
- [x] Size is counted from bytes read.
- [x] Hashing does not depend on filenames, content type, Spring, JPA, AWS, or Azure.
