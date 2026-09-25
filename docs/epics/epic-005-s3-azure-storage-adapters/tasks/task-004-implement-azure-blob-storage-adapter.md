# Task 5.4: Implement Azure BlobStorage Adapter

**Status:** Pending  
**Source:** [PLAN-005](../../../implementation-plans/PLAN-005-s3-azure-storage-adapters.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Implement `BlobStorage` with Azure Blob Storage.

## Files

- Create: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorage.java`
- Create: `dedup4j-storage-azure/src/test/java/com/edem/dedup4j/storage/azure/AzureBlobStorageContractTest.java`

## Steps

- [ ] Implement `put`.
- [ ] Implement `get`.
- [ ] Implement idempotent `delete`.
- [ ] Implement `exists`.
- [ ] Run provider tests with the configured Azure test target or emulator.

## Acceptance

- [ ] Azure round trip satisfies the `BlobStorage` contract.
- [ ] Provider exceptions are mapped to Blob Helper domain exceptions.
- [ ] Normal unit tests do not require real Azure credentials.
