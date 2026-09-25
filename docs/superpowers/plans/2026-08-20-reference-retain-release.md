# Reference Retain and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a lock-aware JPA service that safely retains and releases `AssetContent` references.

**Architecture:** `ReferenceCountService` delegates row retrieval to `AssetContentRepository.findByIdForUpdate`, then mutates the managed entity inside the caller-owned transaction. `AssetContent` exposes package-private count mutation methods so callers retain only the read-only getter. Missing rows use `ContentNotFoundException`; zero-count releases use `ReferenceCountUnderflowException`.

**Tech Stack:** Java 21, Jakarta Persistence 3.2, Hibernate ORM 7.4, H2, JUnit Jupiter.

**Spec:** `docs/epics/epic-002-jpa-metadata-reference-counting/tasks/task-003-add-reference-retain-and-release-service.md`

## Global Constraints

- Use the existing pessimistic-write lookup for reference-count changes.
- Keep `ref_count` from becoming negative.
- The caller owns the `EntityManager` and transaction.
- Do not add physical storage deletion to this service.
- Keep the JPA module framework-independent; Spring and provider SDKs remain out of scope.

### Task 1: Add failing retain behavior test

**Files:**
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ReferenceCountServiceTest.java`

**Interfaces:**
- Consumes: `AssetContent`, `AssetContentRepository`, and the test persistence unit `dedup4j-jpa-test`.
- Produces: A failing test specifying `new ReferenceCountService(repository).retain(contentId)` increments an existing row once.

- [ ] **Step 1: Write the failing test**

Create a JPA integration test following `AssetContentRepositoryTest`'s entity-manager lifecycle. Persist an `AssetContent`, clear the persistence context, begin a transaction, call `retain`, commit, clear, and assert `getRefCount()` is `2L`.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./mvnw -pl dedup4j-jpa -am -Dtest=ReferenceCountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation/test failure because `ReferenceCountService` does not exist yet.

### Task 2: Implement retain and entity mutation seam

**Files:**
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/ReferenceCountService.java`
- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java`

**Interfaces:**
- Consumes: `AssetContentRepository.findByIdForUpdate(UUID)`.
- Produces: `ReferenceCountService(AssetContentRepository)`, `void retain(UUID)`, and `void release(UUID)`.

- [ ] **Step 1: Add the minimal entity mutation methods**

Add package-private methods on `AssetContent` that increment and decrement `refCount` by one. Do not add a public setter or public mutation methods.

- [ ] **Step 2: Add the minimal retain implementation**

`retain(UUID assetContentId)` must null-check the ID, call `findByIdForUpdate`, throw `ContentNotFoundException` when empty, and invoke the entity increment method exactly once. The service must not begin, commit, or roll back transactions.

- [ ] **Step 3: Run the focused test to verify it passes**

Run:

```bash
./mvnw -pl dedup4j-jpa -am -Dtest=ReferenceCountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the retain test passes.

### Task 3: Add release and underflow tests first

**Files:**
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ReferenceCountServiceTest.java`

**Interfaces:**
- Consumes: `ReferenceCountService.release(UUID)` and `ReferenceCountUnderflowException`.
- Produces: Tests for single decrement, zero-count rejection, unchanged count after rejection, and missing content.

- [ ] **Step 1: Add the decrement test**

Persist a row, retain it once to reach `2L`, then release it in a fresh transaction and assert the final count is `1L`.

- [ ] **Step 2: Add the underflow test**

Persist a row, use reflection only in test setup to set its private `refCount` to `0L`, flush that state, then call `release` in a transaction and assert `ReferenceCountUnderflowException`; reload the row and assert the count remains `0L`.

- [ ] **Step 3: Add the missing-content test**

Call `retain(UUID.randomUUID())` and assert `ContentNotFoundException`.

- [ ] **Step 4: Run the focused tests to verify the new tests fail**

Run:

```bash
./mvnw -pl dedup4j-jpa -am -Dtest=ReferenceCountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: release tests fail because release behavior is not implemented; the retain and missing-content tests may expose any API/setup errors and must be corrected before implementation continues.

### Task 4: Implement release and verify the module

**Files:**
- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/ReferenceCountService.java`

- [ ] **Step 1: Implement release minimally**

Load the row with `findByIdForUpdate`, throw `ContentNotFoundException` when absent, check `getRefCount() == 0L`, throw `ReferenceCountUnderflowException` before mutation, and invoke the entity decrement method exactly once otherwise.

- [ ] **Step 2: Run the focused service tests**

Run:

```bash
./mvnw -pl dedup4j-jpa -am -Dtest=ReferenceCountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all `ReferenceCountServiceTest` tests pass.

- [ ] **Step 3: Run the complete JPA module test suite**

Run:

```bash
./mvnw -pl dedup4j-jpa -am test
```

Expected: all JPA mapping, repository, and service tests pass without warnings or errors.

- [ ] **Step 4: Update project indexes and changelog**

Run `git diff HEAD~1 --name-only`, inspect only changed files and their direct neighbors, then update `docs/implementation.md` with the new service/test and append a dated entry to `docs/changelog.md`. No ADR is needed because this implements the already-recorded ADR-003 decision rather than making or reversing an architectural decision.

- [ ] **Step 5: Verify the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors, and only task-related source, test, and documentation changes are present.
