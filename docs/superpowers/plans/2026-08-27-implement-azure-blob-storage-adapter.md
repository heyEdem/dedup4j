# Azure BlobStorage Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Azure Blob Storage provider module so it satisfies the provider-neutral `BlobStorage` contract without requiring Azure credentials in normal tests.

**Architecture:** `AzureBlobStorage` owns an injected or builder-created Azure `BlobContainerClient` and translates Azure SDK calls and `BlobStorageException` status codes at the provider boundary. Uploads stream the caller’s request into Azure with HTTP headers and metadata; reads expose the SDK stream through `BlobResource`; missing reads and idempotent deletes follow the core exception contract.

**Tech Stack:** Java 21, Maven, Azure Storage Blob SDK 12.35.0 via the module-local Azure SDK BOM, JUnit 5, JDK `HttpServer` for a credential-free SDK-backed fake service.

---

### Task 1: Add the Azure contract test

**Files:**
- Create: `dedup4j-storage-azure/src/test/java/com/edem/dedup4j/storage/azure/AzureBlobStorageContractTest.java`

- [x] **Step 1: Write the failing test**

  Build a local HTTP fake that records Azure `PUT` and `DELETE` requests and serves `GET`/`HEAD` responses for one blob. Construct a real Azure `BlobContainerClient` against the fake endpoint using a deterministic development connection string, inject it into `AzureBlobStorage`, and assert:

  - `put` returns provider `azure`, the configured container, request key, size, content type, and a non-null creation time.
  - Azure receives the request body, `Content-Type`, `Content-Disposition`, and metadata.
  - `exists` is true after put and false after delete.
  - `get` returns size, content type, metadata, and the original bytes.
  - deleting twice is safe; reading after deletion throws `ContentNotFoundException`.
  - an Azure 503 response becomes `BlobStorageException`, while Azure 404 maps to `ContentNotFoundException` for get and `false` for exists.

- [x] **Step 2: Run the test to verify it fails**

  Run: `./mvnw -pl dedup4j-storage-azure -Dtest=AzureBlobStorageContractTest test`

  Expected: compilation fails because `AzureBlobStorage` does not yet exist.

### Task 2: Implement the adapter

**Files:**
- Create: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorage.java`

- [x] **Step 1: Add constructors and provider identity**

  Define `PROVIDER = "azure"`, a properties-based constructor that builds a `BlobContainerClient` using the configured connection string and optional endpoint, and an injected-client constructor for tests. Validate the client and properties with `BlobValidationException`; validate nonblank container and object keys before SDK calls.

- [x] **Step 2: Implement `put`**

  Close the request stream after the SDK consumes it. Use `BlobParallelUploadOptions` with the request size, `BlobHttpHeaders` for content type and original filename, and request metadata. Return `StoredBlob` with the object key, Azure provider, container, request size/content type, and the current timestamp. Translate IO and Azure/client failures to `BlobStorageException`.

- [x] **Step 3: Implement `get`, `delete`, and `exists`**

  Read blob properties before opening the stream so `BlobResource` can expose size, content type, and metadata. Map Azure status 404 to `ContentNotFoundException` for reads and `false` for existence checks. Use `deleteIfExists` for idempotent deletion and map other provider failures to `BlobStorageException`.

- [x] **Step 4: Run the focused test to verify it passes**

  Run: `./mvnw -pl dedup4j-storage-azure -Dtest=AzureBlobStorageContractTest test`

  Expected: all Azure contract tests pass without credentials or network access outside the in-process HTTP fake.

### Task 3: Update project documentation and status

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Update the Azure module descriptions**

  Replace the planned-adapter wording with the implemented constructor, streaming, metadata, idempotent deletion, and exception-mapping behavior. Keep provider SDK ownership isolated to the Azure module.

- [x] **Step 2: Mark task 5.4 complete**

  Change task 5.4 to `[x]`, update Epic 5 progress to 4/5 and total progress to 24/30, and add a dated note for the adapter and credential-free contract test.

- [x] **Step 3: Append the changelog entry**

  Add a `2026-08-27` entry naming the Azure adapter, contract tests, and affected modules.

### Task 4: Verify the repository

- [x] **Step 1: Run focused and full tests**

  Run: `./mvnw -pl dedup4j-storage-azure test`

  Run: `./mvnw --batch-mode --no-transfer-progress verify`

- [x] **Step 2: Review the scoped diff**

  Run: `git diff HEAD~1 --name-only` after the implementation commit, then re-scan the changed Azure sources/tests and their direct documentation neighbors. Confirm no core or starter provider SDK dependency changes were introduced.
