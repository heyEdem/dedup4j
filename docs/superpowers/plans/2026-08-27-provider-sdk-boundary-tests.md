# Provider SDK Boundary Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove AWS and Azure SDK dependencies remain isolated to their provider modules and document the credential-free versus external provider test workflow.

**Architecture:** Add a root-level JUnit boundary test that reads the reactor POMs and verifies forbidden provider coordinates are owned only by `dedup4j-storage-s3` and `dedup4j-storage-azure`. Keep provider contract tests in their provider modules, with external credential-dependent tests documented as an explicit opt-in path.

**Tech Stack:** Java 21, Maven, JUnit 5, standard DOM XML parsing, existing provider modules and contract tests.

---

### Task 1: Add the provider dependency boundary test

**Files:**
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/ProviderDependencyBoundaryTest.java`
- Modify: `pom.xml` (root Surefire selector configuration so the reactor command is executable)

- [x] **Step 1: Write the failing test**

Parse the root reactor POM and each module POM. Assert that AWS coordinates occur only in `dedup4j-storage-s3`, Azure coordinates occur only in `dedup4j-storage-azure`, and neither provider coordinate occurs in `dedup4j-core` or `blob-helper-spring-boot-starter`.

- [x] **Step 2: Run the boundary test to verify it fails**

Run: `./mvnw test -Dtest=ProviderDependencyBoundaryTest`

Expected: the test source does not compile or the test is not discovered until the root test dependency and boundary implementation are added.

### Task 2: Implement and verify the boundary

**Files:**
- Modify: `dedup4j-core/src/test/java/com/edem/dedup4j/core/ProviderDependencyBoundaryTest.java`
- Modify: `pom.xml`

- [x] **Step 1: Implement the minimal POM ownership check**

Use JDK XML parsing and the reactor module list from `pom.xml`; report every unexpected provider-coordinate owner in the assertion message. Keep the check test-scoped and avoid adding provider SDKs to the root POM.

- [x] **Step 2: Run the focused boundary test**

Run: `./mvnw test -Dtest=ProviderDependencyBoundaryTest`

Expected: the boundary test passes and reports no AWS/Azure ownership violations.

### Task 3: Document provider test execution

**Files:**
- Create: `docs/provider-testing.md`
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Document default and external test paths**

Explain that S3/Azure contract tests use credential-free in-process fakes and run in their provider modules, while any future credential-dependent tests must be tagged/profiled as opt-in. Include exact Maven commands for the boundary, provider modules, and full reactor.

- [x] **Step 2: Mark task 5.5 complete**

Update Epic 5 to 5/5 and project progress to 25/30, add the implementation/doc entries, and record that no architectural decision changed.

### Task 4: Verify and integrate

- [x] **Step 1: Run the focused boundary test and full reactor verification**

Run `./mvnw test -Dtest=ProviderDependencyBoundaryTest` and `./mvnw --batch-mode --no-transfer-progress verify`.

- [x] **Step 2: Review the scoped diff**

Run `git diff HEAD~1 --name-only`, rescan only changed files and their direct neighbors, commit the milestone, push the existing branch, and update PR #23 against `main`.
