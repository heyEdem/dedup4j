# Task 2.2: Add Repository Identity Lookups and Locks

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADRs:** [ADR-001](../../../adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Add repository operations for identity lookup and locked reference updates.

## Files

- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentRepository.java`
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentRepositoryTest.java`

## Steps

- [x] Add lookup by `hashAlgorithm`, `contentHash`, and `sizeBytes`.
- [x] Add pessimistic locked lookup by id.
- [x] Add test for unique content identity enforcement.
- [x] Run `./mvnw -pl dedup4j-jpa -Dtest=AssetContentRepositoryTest test`.

## Acceptance

- [x] Duplicate identity insert fails.
- [x] Locked lookup can be used by retain/release services.
- [x] Repository does not know about logical application assets.
