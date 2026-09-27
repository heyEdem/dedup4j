# Task 2.5: Add Duplicate-Key Retry Behavior

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADR:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Handle concurrent new-content races by reloading the existing row and incrementing it.

## Files

- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentMutationService.java`
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMutationServiceTest.java`

## Steps

- [x] Add create-or-retain method keyed by content identity.
- [x] Catch duplicate-key failures from concurrent inserts.
- [x] Reload the existing content row and increment `ref_count`.
- [x] Run `./mvnw -pl dedup4j-jpa -Dtest=AssetContentMutationServiceTest test`.

## Acceptance

- [x] Duplicate insert race returns the existing content row.
- [x] Duplicate race increments `ref_count` exactly once.
- [x] Storage adapters are not involved in database mutation.
