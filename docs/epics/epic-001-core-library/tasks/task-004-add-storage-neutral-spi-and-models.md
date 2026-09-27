# Task 1.4: Add Storage-Neutral SPI and Models

**Status:** Complete
**Source:** [PLAN-001](../../../implementation-plans/PLAN-001-core-library.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Define provider-neutral storage and service models used by every adapter.

## Files

- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/BlobStorage.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/PutBlobRequest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/StoredBlob.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/BlobResource.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/StoreBlobCommand.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobReference.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/*.java`

## Steps

- [x] Define `BlobStorage.put`, `get`, `delete`, and `exists`.
- [x] Add immutable request/response records.
- [x] Add domain exceptions for validation, hashing, storage, content not found, and reference count underflow.
- [x] Run `./mvnw -pl dedup4j-core test`.

## Acceptance

- [x] Core API exposes no provider SDK type.
- [x] Models include object key, provider, bucket/container, size, content type, and metadata.
- [x] Exceptions are domain-level and provider-neutral.
