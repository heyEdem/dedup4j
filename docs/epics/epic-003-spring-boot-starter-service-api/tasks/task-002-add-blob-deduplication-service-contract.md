# Task 3.2: Add BlobDeduplicationService Contract

**Status:** Complete
**Source:** [PLAN-003](../../../implementation-plans/PLAN-003-spring-boot-starter-service-api.md)  
**ADRs:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md), [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Define the app-facing service API for store, retain, release, and get.

## Files

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/BlobDeduplicationService.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/BlobDeduplicationServiceContractTest.java`

## Steps

- [x] Define `store(StoreBlobCommand)`, `retain(UUID)`, `release(UUID)`, and `get(UUID)`.
- [x] Return provider-neutral `BlobReference` and `BlobResource`.
- [x] Add a contract test for API shape and missing-content behavior.
- [x] Run the contract test through the reactor with `./mvnw -pl dedup4j-spring-boot-starter -am -Dtest=BlobDeduplicationServiceContractTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Acceptance

- [x] API exposes no AWS or Azure types.
- [x] Service does not create logical application assets.
- [x] Service can be used by application-owned controllers.
