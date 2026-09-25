# Task 2.6: Add Concurrent Duplicate Upload Integration Test

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADR:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Prove parallel duplicate uploads converge on one content row.

## Files

- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ConcurrentUploadIntegrationTest.java`

## Steps

- [x] Start parallel workers using identical content identity.
- [x] Execute create-or-retain concurrently.
- [x] Assert one `AssetContent` row exists.
- [x] Assert `ref_count` equals the worker count.
- [x] Run `./mvnw -pl dedup4j-jpa -Dtest=ConcurrentUploadIntegrationTest test`.

## Acceptance

- [x] Concurrent duplicate uploads create one row.
- [x] Final reference count is correct.
- [x] The test fails if the unique constraint or retry path is removed.
