# Local Storage Service Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify that the deduplication service stores content through the local filesystem adapter, reuses the same physical object for duplicate content, reads it back, and deletes it after the final reference is released.

**Architecture:** Add a starter-module integration test that constructs the existing provider-neutral service with a real `LocalBlobStorage`, a temporary filesystem root, and the existing Hibernate/H2 test persistence unit. Add the local adapter as a test-scoped Maven dependency because the test belongs to the starter module while production module dependencies remain provider-neutral.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, Jakarta Persistence, Hibernate ORM, H2, `dedup4j-core`, `dedup4j-jpa`, and `dedup4j-storage-local`.

---

### Task 1: Add the local adapter test dependency

**Files:**
- Modify: `blob-helper-spring-boot-starter/pom.xml`

- [x] **Step 1: Add the test-scoped reactor dependency**

Add `com.edem:dedup4j-storage-local:${project.version}` with `<scope>test</scope>` alongside the starter's existing test dependencies. This makes the concrete local provider available only to integration tests.

- [x] **Step 2: Verify the dependency resolves**

Run: `./mvnw -pl blob-helper-spring-boot-starter -am -DskipTests test-compile`

Expected: `BUILD SUCCESS` and compilation of the starter test sources.

### Task 2: Write the failing service integration test

**Files:**
- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/LocalStorageDeduplicationIntegrationTest.java`

- [x] **Step 1: Define the end-to-end test**

Create a JUnit test using the existing `blob-helper-starter-test` persistence unit and a JUnit `@TempDir`. Construct `LocalBlobStorageProperties` with the temporary directory, then wire `LocalBlobStorage`, `AssetContentRepository`, `ReferenceCountService`, `AssetContentMutationService`, `Sha256ContentHasher`, and `HashObjectKeyStrategy` into `DefaultBlobDeduplicationService`.

The test should:

```java
BlobReference first = service.store(command("photo.jpg"));
assertFalse(first.duplicate());
assertTrue(storage.exists(first.objectKey()));
assertEquals(CONTENT, read(service.get(first.assetContentId())));
assertEquals(1L, regularFileCount(root));

BlobReference duplicate = service.store(command("copy.jpg"));
assertTrue(duplicate.duplicate());
assertEquals(first.assetContentId(), duplicate.assetContentId());
assertEquals(1L, regularFileCount(root));

service.release(first.assetContentId());
assertTrue(storage.exists(first.objectKey()));
service.release(duplicate.assetContentId());
assertFalse(storage.exists(first.objectKey()));
assertEquals(0L, regularFileCount(root));
```

Use one active transaction for the complete flow, roll it back in `@AfterEach`, close the `EntityManager`, and close the static `EntityManagerFactory` in `@AfterAll`. Use `Files.walk(root)` for the regular-file count and close every returned `BlobResource` with try-with-resources.

- [x] **Step 2: Run the focused test to verify the expected failure**

Run: `./mvnw -pl blob-helper-spring-boot-starter -am -Dtest=LocalStorageDeduplicationIntegrationTest test`

Expected: the test initially fails to compile or execute until the dependency and test wiring are present; after the dependency is added, any remaining failure must identify a real test/setup issue rather than an unresolved class.

### Task 3: Verify the integration and project documentation

**Files:**
- Modify: `docs/taskindex.md`
- Modify: `docs/implementation.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Run the focused test after implementation**

Run: `./mvnw -pl blob-helper-spring-boot-starter -am -Dtest=LocalStorageDeduplicationIntegrationTest test`

Expected: `BUILD SUCCESS`; the test proves one physical file for two logical references and deletion only after the second release.

- [x] **Step 2: Run the full reactor verification**

Run: `./mvnw verify`

Expected: `BUILD SUCCESS` with all existing module tests passing.

- [x] **Step 3: Review changed files and update indexed docs**

Run: `git diff HEAD~1 --name-only` after committing the implementation, then re-scan only the changed test/POM neighbors. Mark task 4.4 complete and Epic 4 complete in `docs/taskindex.md`, add the new integration test and test-only dependency to `docs/implementation.md`, and append a dated entry to `docs/changelog.md`. No ADR is needed because this adds coverage and test wiring without changing a production architectural decision.

- [x] **Step 4: Commit the milestone task**

Run:

```bash
git add blob-helper-spring-boot-starter/pom.xml \
  blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/LocalStorageDeduplicationIntegrationTest.java \
  docs/taskindex.md docs/implementation.md docs/changelog.md \
  docs/superpowers/plans/2026-08-26-local-storage-service-integration-tests.md
git commit -m "test: cover local storage service integration"
```

Expected: a new commit containing only the task implementation and its documentation.
