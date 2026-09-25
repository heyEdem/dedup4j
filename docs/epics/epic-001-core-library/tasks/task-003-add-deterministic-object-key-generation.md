# Task 1.3: Add Deterministic Object Key Generation

**Status:** Done  
**Source:** [PLAN-001](../../../implementation-plans/PLAN-001-core-library.md)  
**ADR:** [ADR-001](../../../adrs/ADR-001-content-identity-and-core-boundaries.md)

## Goal

Generate stable storage keys from content identity instead of user filenames.

## Files

- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/key/ObjectKeyStrategy.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/key/HashObjectKeyStrategy.java`
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/key/HashObjectKeyStrategyTest.java`

## Steps

- [x] Write `HashObjectKeyStrategyTest.generatesDeterministicKey`.
- [x] Implement key format `{prefix}/{algorithm}/{first_two_hash_chars}/{content_hash}`.
- [x] Normalize algorithm names to lowercase path segments.
- [x] Run `./mvnw -pl dedup4j-core -Dtest=HashObjectKeyStrategyTest test`.

## Acceptance

- [x] The same content identity always generates the same key.
- [x] User filenames are not part of the storage key.
- [x] Empty prefix still generates a valid relative key.
