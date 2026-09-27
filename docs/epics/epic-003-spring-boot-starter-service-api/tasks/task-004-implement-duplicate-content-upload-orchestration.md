# Task 3.4: Implement Duplicate-Content Upload Orchestration

**Status:** Complete
**Source:** [PLAN-003](../../../implementation-plans/PLAN-003-spring-boot-starter-service-api.md)  
**ADR:** [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Reuse existing physical content for byte-identical uploads.

## Files

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/BlobDeduplicationServiceTest.java`

## Steps

- [x] Write `reusesDuplicateContent`.
- [x] Detect existing content by hash algorithm, hash, and size.
- [x] Increment `ref_count`.
- [x] Skip `BlobStorage.put`.
- [x] Run the starter test through the Maven reactor.

## Acceptance

- [x] Duplicate content does not write storage.
- [x] Duplicate content increments `ref_count` once.
- [x] Returned `BlobReference` has `duplicate = true`.
