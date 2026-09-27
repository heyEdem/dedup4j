# Task 3.3: Implement New-Content Upload Orchestration

**Status:** Complete
**Source:** [PLAN-003](../../../implementation-plans/PLAN-003-spring-boot-starter-service-api.md)  
**ADR:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Store unseen bytes by hashing, uploading once, and creating metadata with `ref_count = 1`.

## Files

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/BlobDeduplicationServiceTest.java`

## Steps

- [x] Write `storesNewContent`.
- [x] Hash the upload stream.
- [x] Look up content identity through `AssetContentMutationService.createOrRetain`.
- [x] Generate object key and call `BlobStorage.put`.
- [x] Persist `AssetContent` with `ref_count = 1`.
- [x] Run the starter test through the Maven reactor.

## Acceptance

- [x] New content writes storage once.
- [x] New content creates one metadata row.
- [x] Returned `BlobReference` has `duplicate = false`.
