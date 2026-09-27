# Repository Identity Lookups and Locks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a framework-independent JPA repository that finds `AssetContent` by its unique content identity and obtains a pessimistic write lock by id.

**Architecture:** `AssetContentRepository` wraps a caller-owned `EntityManager`; lookup methods return `Optional<AssetContent>` and do not know about logical application assets. The identity query uses all three identity fields, while the id lookup applies `LockModeType.PESSIMISTIC_WRITE` so later retain/release services can safely mutate the row inside the caller's transaction.

**Tech Stack:** Java 21, Jakarta Persistence 3.2, Hibernate ORM 7.4, H2, JUnit Jupiter.

---

### Task 1: Add repository behavior tests

**Files:**
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentRepositoryTest.java`

- [x] **Step 1: Write tests for identity lookup, locked id lookup, and duplicate identity rejection**

  Bootstrap the existing `dedup4j-jpa-test` persistence unit, persist one `AssetContent`, assert the identity lookup finds it only when algorithm/hash/size all match, assert the locked lookup returns the same row, and assert a second row with the same identity fails at transaction commit.

- [x] **Step 2: Run the repository test before implementation**

  Run `./mvnw -pl dedup4j-jpa -Dtest=AssetContentRepositoryTest test`.

  Expected: compilation failure because `AssetContentRepository` does not exist.

### Task 2: Implement the repository

**Files:**
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentRepository.java`

- [x] **Step 1: Add an EntityManager-backed repository**

  Implement `findByIdentity(String hashAlgorithm, String contentHash, long sizeBytes)` with a typed JPQL query using all identity fields, and implement `findByIdForUpdate(UUID id)` with `EntityManager.find(..., LockModeType.PESSIMISTIC_WRITE)`. Return `Optional.empty()` for missing rows and reject a null `EntityManager` in the constructor.

- [x] **Step 2: Run the focused repository test**

  Run `./mvnw -pl dedup4j-jpa -Dtest=AssetContentRepositoryTest test`.

  Expected: all repository tests pass.

### Task 3: Update indexed project documentation

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/README.md`
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/tasks/task-002-add-repository-identity-lookups-and-locks.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Record the repository entry points and completed task state**

  Document the EntityManager-backed repository and its lookup/locking semantics, mark task 2.2 complete in the status docs, and append the dated changelog entry.

- [x] **Step 2: Run module and full verification**

  Run `./mvnw -pl dedup4j-jpa test` and `./mvnw verify`.

  Expected: both commands exit successfully with all tests passing.

- [x] **Step 3: Review the feature diff and changed-file neighbors**

  Run `git diff HEAD~1 --name-only` as required by the project instructions, then inspect only the changed Java files and their package-local neighbors before reporting the result.
