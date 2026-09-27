# PLAN-004: Local Storage Adapter and Integration Tests

**Date:** 2026-07-04  
**Status:** Todo  
**Implements:** ADR-004  
**Estimated complexity:** Medium

## Goal

Add deterministic local filesystem storage for development and integration tests.

## Background

The first complete service path needs a storage provider that requires no cloud credentials.

## What to Build

- Add `dedup4j-storage-local` module.
- Implement `LocalBlobStorage`.
- Add local storage properties for root directory.
- Implement `put`, `get`, `delete`, and `exists`.
- Ensure missing-object delete is idempotent by default.
- Add integration tests that use temporary directories.

## Where the Logic Lives (from Q3)

| Logic | Location |
|-------|----------|
| Local provider implementation | `dedup4j-storage-local/src/main/java/.../local/LocalBlobStorage.java` |
| Local provider config | `dedup4j-storage-local` and starter auto-configuration |
| Test storage root | JUnit temporary directory |

## Acceptance Criteria (from Q4)

- [ ] **LocalBlobStorageIntegrationTest.putGetDeleteRoundTrip:** Given local storage config, when storing, reading, and deleting a blob, then filesystem state matches each operation.
- [ ] **BlobStorageDeleteTest.missingObjectIsAlreadyDeletedByDefault:** Given a missing object, when deleted, then no exception is thrown unless strict mode is enabled.

## Out of Scope (from Q5)

- Cloud provider SDKs — not part of local storage.
- Public URL generation — not supported.
- Database mutation — storage adapters only perform blob IO.

## Implementation Notes

- Sanitize object keys to prevent path traversal.
- Use temporary directories in tests.
- Keep adapter behavior aligned with `BlobStorage`.

## Definition of Done

- [ ] All acceptance criteria tests pass
- [ ] No out-of-scope files were modified
- [ ] ADR invariants are enforced in code
- [ ] PR reviewed and merged
