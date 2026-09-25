# PLAN-005: S3 and Azure Storage Adapters

**Date:** 2026-07-04  
**Status:** Complete
**Implements:** ADR-004  
**Estimated complexity:** Large

## Goal

Add S3 and Azure Blob Storage modules that implement the provider-neutral `BlobStorage` SPI.

## Background

The library must support cloud storage providers without leaking provider SDK dependencies into core or starter code.

## What to Build

- Add `dedup4j-storage-s3` module.
- Add S3 properties for bucket, region, and key prefix.
- Implement S3 `put`, `get`, `delete`, and `exists`.
- Add `dedup4j-storage-azure` module.
- Add Azure properties for container and connection string.
- Implement Azure `put`, `get`, `delete`, and `exists`.
- Add provider tests separated from normal unit tests.

## Where the Logic Lives (from Q3)

| Logic | Location |
|-------|----------|
| S3 SDK implementation | `dedup4j-storage-s3/src/main/java/.../s3` |
| Azure SDK implementation | `dedup4j-storage-azure/src/main/java/.../azure` |
| Provider-specific properties | Provider modules and starter property binding |
| Provider-neutral API | `dedup4j-core` |

## Acceptance Criteria (from Q4)

- [ ] **S3BlobStorageContractTest.putGetDeleteRoundTrip:** Given S3-compatible test storage, when storing, reading, and deleting a blob, then storage state matches each operation.
- [ ] **AzureBlobStorageContractTest.putGetDeleteRoundTrip:** Given Azure test storage, when storing, reading, and deleting a blob, then storage state matches each operation.
- [x] **ProviderDependencyBoundaryTest.providerSdksStayOutOfCoreAndStarter:** Given the Maven reactor, then AWS and Azure SDK dependencies appear only in provider modules.

## Out of Scope (from Q5)

- AWS/Azure SDK code in `dedup4j-core` or the starter — forbidden.
- Normal unit tests requiring cloud credentials — provider tests must be isolated.
- Public URL generation — not included by default.

## Implementation Notes

- Prefer emulator/container tests where practical.
- Mark external provider tests with Maven profiles or tags.
- Keep provider exceptions mapped to Blob Helper domain exceptions at the adapter boundary.

## Definition of Done

- [x] All acceptance criteria tests pass
- [x] No out-of-scope files were modified
- [x] ADR invariants are enforced in code
- [ ] PR reviewed and merged
