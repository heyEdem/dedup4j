# S3 BlobStorage Adapter Implementation Plan

**Goal:** Implement the S3 provider module's `BlobStorage` contract with streaming reads, idempotent deletion, existence checks, and provider-to-domain exception mapping.

**Architecture:** Keep AWS SDK usage inside `dedup4j-storage-s3`. Support both a properties-based client constructor and an injected `S3Client` constructor so normal wiring can create the provider client while unit tests remain deterministic and credential-free.

## Task 1: Define the contract behavior with a provider-free test double

- [x] Add `S3BlobStorageContractTest` covering put/get/delete/exists round trip.
- [x] Cover missing reads, missing existence checks, idempotent delete, and provider failure mapping.
- [x] Run the focused module tests and observe the expected red state before implementation.

## Task 2: Implement the S3 adapter

- [x] Implement streaming `put` using `RequestBody.fromInputStream`.
- [x] Implement streaming `get` using `ResponseInputStream` and response metadata.
- [x] Implement idempotent `delete` and `headObject`-based `exists`.
- [x] Map S3 404 responses to core not-found semantics and other SDK failures to `BlobStorageException`.
- [x] Support bucket, region, endpoint override, and path-style client configuration.

## Task 3: Verify and update indexed documentation

- [x] Run focused and full Maven verification.
- [x] Update implementation/task/epic indexes and append the dated changelog entry.
- [x] Re-scan changed files and direct neighbors after committing.
- [x] Commit, push the milestone branch, and create a pull request targeting `main`.
