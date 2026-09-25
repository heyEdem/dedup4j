# Task 4.2: Implement Local Put Get Delete Exists

**Status:** Pending  
**Source:** [PLAN-004](../../../implementation-plans/PLAN-004-local-storage-adapter-and-integration-tests.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Implement the `BlobStorage` contract using the local filesystem.

## Files

- Create: `dedup4j-storage-local/src/main/java/com/edem/dedup4j/storage/local/LocalBlobStorage.java`
- Create: `dedup4j-storage-local/src/test/java/com/edem/dedup4j/storage/local/LocalBlobStorageIntegrationTest.java`

## Steps

- [ ] Implement `put`.
- [ ] Implement `get`.
- [ ] Implement idempotent `delete`.
- [ ] Implement `exists`.
- [ ] Run `./mvnw -pl dedup4j-storage-local -Dtest=LocalBlobStorageIntegrationTest test`.

## Acceptance

- [ ] Put/get/delete round trip passes.
- [ ] Missing-object delete succeeds by default.
