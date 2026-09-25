# Task 2.4: Add Final-Reference Delete Orchestration

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Delete physical storage only when the final logical reference is released.

## Files

- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/ReferenceCountService.java`
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ReferenceCountServiceTest.java`

## Steps

- [x] Accept a `BlobStorage` service collaborator at the release boundary.
- [x] Keep physical storage when `ref_count` remains above zero.
- [x] Delegate physical deletion when `ref_count` reaches zero; zero-count metadata remains available for later reconciliation/tombstone policy.
- [x] Run `./mvnw -pl dedup4j-jpa -Dtest=ReferenceCountServiceTest test`.

## Acceptance

- [x] `ref_count = 2` releases to `1` without storage delete.
- [x] `ref_count = 1` triggers one physical delete.
- [x] Missing storage objects are treated as already deleted by default through the idempotent `BlobStorage.delete` contract.
