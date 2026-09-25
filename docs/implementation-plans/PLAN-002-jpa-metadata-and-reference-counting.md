# PLAN-002: JPA Metadata and Reference Counting

**Date:** 2026-07-04  
**Status:** Todo  
**Implements:** ADR-001, ADR-002, ADR-003  
**Estimated complexity:** Large

## Goal

Add relational metadata persistence for physical blob content, including uniqueness, locking, reference counting, and underflow protection.

## Background

Deduplication depends on a durable `AssetContent` record with a unique content identity and accurate `ref_count`.

## What to Build

- Add `dedup4j-jpa` module.
- Add `AssetContent` JPA entity mapped to `blob_asset_content`.
- Add unique constraint on `hash_algorithm`, `content_hash`, and `size_bytes`.
- Add indexes for hash, object key, and reference count.
- Add repository methods for lookup by identity, locked lookup by id, insert, increment, and decrement.
- Add `ReferenceCountService` with retain/release operations and underflow protection.
- Add duplicate-key retry support for concurrent new-content races.

## Where the Logic Lives (from Q3)

| Logic | Location |
|-------|----------|
| Physical content metadata | `dedup4j-jpa/src/main/java/.../jpa/AssetContent.java` |
| Lookup and locks | `dedup4j-jpa/src/main/java/.../jpa/AssetContentRepository.java` |
| Reference count mutation | `dedup4j-jpa/src/main/java/.../jpa/ReferenceCountService.java` |
| Duplicate-key retry | `dedup4j-jpa` service/repository boundary |

## Acceptance Criteria (from Q4)

- [ ] **AssetContentRepositoryTest.enforcesContentIdentityUniqueConstraint:** Given two rows with the same algorithm, hash, and size, when both are inserted, then the second insert fails.
- [ ] **ReferenceCountServiceTest.retainIncrementsOnce:** Given an existing row, when retained once, then `ref_count` increases by one.
- [ ] **ReferenceCountServiceTest.releaseDoesNotDeleteWhenReferencesRemain:** Given `ref_count = 2`, when released, then `ref_count = 1` and storage delete is not called.
- [ ] **ReferenceCountServiceTest.releaseFinalReferenceDeletesPhysicalObject:** Given `ref_count = 1`, when released, then storage delete is called once.
- [ ] **ReferenceCountServiceTest.releaseUnderflowFails:** Given `ref_count = 0`, when released, then a reference count underflow exception is thrown.
- [x] **ConcurrentUploadIntegrationTest.concurrentDuplicatesCreateOneRow:** Given parallel uploads of identical bytes, when all complete, then one row exists and `ref_count` equals the upload count.

## Out of Scope (from Q5)

- Controllers — deduplication rules must not live there.
- Storage adapters — must not mutate database state.
- Consuming application logical asset schemas — not assumed or created.
- JPA entity callbacks for physical deletes — forbidden.

## Implementation Notes

- Use row locks for reference count changes.
- Retry duplicate-key races by reloading the existing content row and incrementing it.
- Keep migration tooling as a separate decision if Flyway/Liquibase support is added later.

## Definition of Done

- [ ] All acceptance criteria tests pass
- [ ] No out-of-scope files were modified
- [ ] ADR invariants are enforced in code
- [ ] PR reviewed and merged
