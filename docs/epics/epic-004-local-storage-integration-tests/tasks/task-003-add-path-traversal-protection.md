# Task 4.3: Add Path Traversal Protection

**Status:** Pending  
**Source:** [PLAN-004](../../../implementation-plans/PLAN-004-local-storage-adapter-and-integration-tests.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Prevent local storage keys from escaping the configured root directory.

## Files

- Modify: `dedup4j-storage-local/src/main/java/com/edem/dedup4j/storage/local/LocalBlobStorage.java`
- Modify: `dedup4j-storage-local/src/test/java/com/edem/dedup4j/storage/local/LocalBlobStorageIntegrationTest.java`

## Steps

- [ ] Normalize resolved paths.
- [ ] Reject keys that resolve outside the storage root.
- [ ] Add tests for `../` and absolute-path attempts.
- [ ] Run `./mvnw -pl dedup4j-storage-local -Dtest=LocalBlobStorageIntegrationTest test`.

## Acceptance

- [ ] Path traversal attempts fail before file IO.
- [ ] Valid nested keys still work.
