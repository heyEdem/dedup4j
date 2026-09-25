# Task 5.2: Implement S3 BlobStorage Adapter

**Status:** Complete
**Source:** [PLAN-005](../../../implementation-plans/PLAN-005-s3-azure-storage-adapters.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Implement `BlobStorage` with AWS S3 or S3-compatible object storage.

## Files

- Create: `dedup4j-storage-s3/src/main/java/com/edem/dedup4j/storage/s3/S3BlobStorage.java`
- Create: `dedup4j-storage-s3/src/test/java/com/edem/dedup4j/storage/s3/S3BlobStorageContractTest.java`

## Steps

- [x] Implement `put`.
- [x] Implement `get`.
- [x] Implement idempotent `delete`.
- [x] Implement `exists`.
- [x] Run the credential-free provider contract tests with an injected SDK client; no external S3-compatible target is configured in this repository.

## Acceptance

- [x] S3 round trip satisfies the `BlobStorage` contract.
- [x] Provider exceptions are mapped to Blob Helper domain exceptions.
- [x] Normal unit tests do not require real AWS credentials.
