# Task 4.4: Add Local Storage Service Integration Tests

**Status:** Complete
**Source:** [PLAN-004](../../../implementation-plans/PLAN-004-local-storage-adapter-and-integration-tests.md)  
**ADRs:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md), [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Test the complete service path with local storage.

## Files

- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/LocalStorageDeduplicationIntegrationTest.java`

## Steps

- [x] Configure local provider with a temporary directory.
- [x] Store new content and verify file exists.
- [x] Store duplicate content and verify no second file is created.
- [x] Release both references and verify final delete.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=LocalStorageDeduplicationIntegrationTest test`.

## Acceptance

- [x] End-to-end local flow passes without cloud credentials.
- [x] Duplicate upload skips physical write.
