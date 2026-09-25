# Duplicate-Key Retry Behavior Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JPA mutation service that creates one `AssetContent` row for new content and converts a concurrent duplicate insert into one locked reference-count increment.

**Architecture:** `AssetContentMutationService` wraps the existing caller-owned `EntityManager` and `AssetContentRepository`. It first checks the complete identity tuple, increments an existing row under a pessimistic write lock, or persists and flushes a candidate so a concurrent unique-constraint collision is observed inside the method; duplicate-key failures roll back the failed resource-local transaction, begin a clean retry transaction, reload the identity with a row lock, and increment exactly once. The service has no `BlobStorage` dependency.

**Tech Stack:** Java 21, Jakarta Persistence 3.2, Hibernate ORM 7.4, H2, JUnit Jupiter.

**Spec:** `docs/epics/epic-002-jpa-metadata-reference-counting/tasks/task-005-add-duplicate-key-retry-behavior.md`

## Global Constraints

- Use the existing `AssetContent` identity tuple: `hash_algorithm + content_hash + size_bytes`.
- Use the existing pessimistic-write lookup for reference-count changes.
- Keep the caller-owned `EntityManager`; preserve the caller's transaction on normal paths and restart only the failed resource-local transaction on a duplicate-key retry.
- Detect duplicate-key failures without adding Hibernate, Spring, or storage-provider dependencies to production code.
- Do not involve `BlobStorage` in database mutation.

---

### Task 1: Add failing mutation-service tests

**Files:**
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMutationServiceTest.java`

**Interfaces:**
- Consumes: `AssetContent`, `AssetContentRepository`, and the existing `dedup4j-jpa-test` persistence unit.
- Produces: Tests specifying `AssetContentMutationService(EntityManager)` and `AssetContent createOrRetain(AssetContent candidate)`.

- [ ] **Step 1: Write the failing tests**

  Add a JPA integration test with the existing entity-manager lifecycle. Cover these behaviors:

  ```java
  @Test
  void createsNewContentWithOneReference() {
      AssetContent candidate = newContent("a".repeat(64));

      entityManager.getTransaction().begin();
      AssetContent created = service.createOrRetain(candidate);
      entityManager.getTransaction().commit();
      entityManager.clear();

      AssetContent persisted = entityManager.find(AssetContent.class, created.getId());
      assertEquals(1L, persisted.getRefCount());
  }

  @Test
  void retainsExistingContentOnceWithoutCreatingAnotherRow() {
      AssetContent existing = persistContent("b".repeat(64));
      AssetContent duplicate = newContent(existing.getContentHash());

      entityManager.clear();
      entityManager.getTransaction().begin();
      AssetContent retained = service.createOrRetain(duplicate);
      entityManager.getTransaction().commit();
      entityManager.clear();

      assertEquals(existing.getId(), retained.getId());
      assertEquals(2L, entityManager.find(AssetContent.class, existing.getId()).getRefCount());
  }
  ```

  Add a duplicate-key race test using two entity managers and transactions: let one transaction insert the candidate and hold it uncommitted, let the second mutation service attempt the same identity, commit the first transaction so the second flush observes the unique conflict, then assert the second call returns the first row and the committed count is `2L`. The test must assert only database state and returned entities; it must not introduce a storage collaborator.

- [ ] **Step 2: Run the focused test to verify it fails**

  Run:

  ```bash
  ./mvnw -pl dedup4j-jpa -am -Dtest=AssetContentMutationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
  ```

  Expected: compilation failure because `AssetContentMutationService` does not exist yet.

### Task 2: Implement create-or-retain and duplicate-key retry

**Files:**
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentMutationService.java`

**Interfaces:**
- Consumes: `AssetContentRepository.findByIdentity(String, String, long)` and `findByIdForUpdate(UUID)`; package-private `AssetContent.incrementRefCount()`.
- Produces: `AssetContentMutationService(EntityManager)` and `AssetContent createOrRetain(AssetContent candidate)`.

- [ ] **Step 1: Add the minimal service structure**

  Store a non-null `EntityManager` and an `AssetContentRepository`. Reject a null candidate with `Objects.requireNonNull`. Extract the candidate's algorithm, hash, and size as the lookup key.

- [ ] **Step 2: Implement the existing-row path**

  Look up the complete identity tuple. If a row exists, reload it using `findByIdForUpdate`, call `incrementRefCount()` exactly once, and return that managed row. Do not persist the candidate and do not call any storage API.

- [ ] **Step 3: Implement the new-row path**

  If no row exists, call `entityManager.persist(candidate)` and `entityManager.flush()`, then return the candidate. The explicit flush is required so a unique-constraint race is caught by this method rather than by the caller's later commit.

- [ ] **Step 4: Implement portable duplicate-key detection and retry**

  Catch `PersistenceException` from the insert/flush path only. Walk its cause chain and treat a `SQLException` with SQL state `23505` as a duplicate-key failure. Roll back the failed resource-local transaction and begin a clean retry transaction, clear the persistence context, reload the identity row, acquire the existing row through `findByIdForUpdate`, increment once, and return it. Re-throw other persistence failures unchanged; if the identity row cannot be reloaded, re-throw the original failure.

  Production code must use only Jakarta Persistence and JDK types for this classification; do not import Hibernate classes.

- [ ] **Step 5: Run the focused service tests**

  Run:

  ```bash
  ./mvnw -pl dedup4j-jpa -am -Dtest=AssetContentMutationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
  ```

  Expected: all create, existing-row, and duplicate-race tests pass.

### Task 3: Update indexed documentation and verify the module

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/README.md`
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/tasks/task-005-add-duplicate-key-retry-behavior.md`
- Modify: `docs/changelog.md`

- [ ] **Step 1: Update the source indexes**

  Add `AssetContentMutationService` and `AssetContentMutationServiceTest` to the JPA entry-point and per-module sections. Document that the service flushes inserts to detect unique-identity races, retries by locked reload, increments once, owns no storage behavior, and leaves transaction lifecycle with the caller.

- [ ] **Step 2: Mark the task and Epic 2 progress complete**

  Change task 2.5 to `[x]` in `docs/taskindex.md` and the Epic 2 README, update the task checklist to `[x]`, and change Epic 2 progress from `4/6` to `5/6`. Append a dated changelog entry for 2026-08-20 listing the affected JPA module and Epic 2 documentation.

- [ ] **Step 3: Check the architectural-decision gate**

  This implements the duplicate-key retry already recorded in ADR-002 and does not make or reverse an architectural decision, so do not add an ADR entry.

- [ ] **Step 4: Run focused and full verification**

  Run:

  ```bash
  ./mvnw -pl dedup4j-jpa -Dtest=AssetContentMutationServiceTest test
  ./mvnw -pl dedup4j-jpa test
  ./mvnw verify
  git diff --check
  git diff HEAD~1 --name-only
  git status --short
  ```

  Expected: every Maven command exits successfully, the diff has no whitespace errors, and changed files are limited to task-related source, tests, and documentation.

- [ ] **Step 5: Re-scan changed files and direct neighbors**

  Read the changed Java files and the other files in `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa` and `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa`, then confirm the implementation and indexed documentation agree before reporting completion.
