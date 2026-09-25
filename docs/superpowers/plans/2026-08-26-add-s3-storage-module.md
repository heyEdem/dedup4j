# S3 Storage Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated `dedup4j-storage-s3` Maven module with AWS SDK v2 support and configurable S3 bucket, region, endpoint override, and path-style access properties.

**Architecture:** Register a new provider module in the root reactor. Keep the AWS SDK BOM and `s3` dependency inside that module, with only `dedup4j-core` as its project dependency; defer all `BlobStorage` implementation behavior to task 5.2. Keep the properties class plain and provider-specific so the starter and core remain free of AWS types.

**Tech Stack:** Java 21, Maven, AWS SDK for Java 2.x, JUnit Jupiter, and `dedup4j-core`.

---

### Task 1: Define S3 configuration behavior with tests

**Files:**
- Create: `dedup4j-storage-s3/src/main/java/com/edem/dedup4j/storage/s3/S3BlobStorageProperties.java`
- Create: `dedup4j-storage-s3/src/test/java/com/edem/dedup4j/storage/s3/S3BlobStoragePropertiesTest.java`

- [x] **Step 1: Write the failing properties tests**

Add tests proving the properties object accepts a bucket, region, URI endpoint override, and path-style access flag, and that path-style access defaults to `false`.

- [x] **Step 2: Run the tests to verify the expected failure**

Run: `./mvnw -pl dedup4j-storage-s3 test`

Expected: the module is not yet available in the reactor, so Maven fails before executing the new tests.

- [x] **Step 3: Implement the minimal properties class**

Add private fields `String bucket`, `String region`, `URI endpointOverride`, and `boolean pathStyleAccess` with standard getters/setters. Do not add SDK client creation or validation that belongs to task 5.2.

- [x] **Step 4: Run the properties tests to verify they pass**

Run: `./mvnw -pl dedup4j-storage-s3 test`

Expected: the module builds and the properties tests pass.

### Task 2: Add the isolated Maven module

**Files:**
- Modify: `pom.xml`
- Create: `dedup4j-storage-s3/pom.xml`

- [x] **Step 1: Add the module to the reactor**

Add `<module>dedup4j-storage-s3</module>` to the root module list.

- [x] **Step 2: Add module-local AWS dependency management**

Configure the module with an AWS SDK v2 BOM and the `software.amazon.awssdk:s3` dependency. Add `dedup4j-core` as the only project-module dependency and JUnit Jupiter as test scope. Do not add AWS dependencies to root dependency management or any existing module.

- [x] **Step 3: Compile and run the module tests**

Run: `./mvnw -pl dedup4j-storage-s3 test`

Expected: `BUILD SUCCESS` with the S3 properties tests passing and no AWS credentials required.

### Task 3: Verify isolation and update project documentation

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/changelog.md`
- Modify: `docs/epics/epic-005-s3-azure-storage-adapters/README.md`
- Modify: `docs/epics/epic-005-s3-azure-storage-adapters/tasks/task-001-add-s3-storage-module.md`

- [x] **Step 1: Verify the full reactor**

Run: `./mvnw verify`

Expected: `BUILD SUCCESS`; existing core and starter boundary tests continue to pass while the new module compiles with AWS SDK dependencies.

- [x] **Step 2: Check dependency isolation**

Run: `./mvnw dependency:tree -pl dedup4j-core` and `./mvnw dependency:tree -pl dedup4j-spring-boot-starter`

Expected: neither output contains `software.amazon.awssdk`; the S3 module is the only module that resolves the AWS SDK.

- [x] **Step 3: Update indexed docs**

Run: `git diff HEAD~1 --name-only` after committing, then re-scan only the changed module/POM neighbors. Record the new module, properties class, module-local AWS BOM, and test coverage in the architecture and implementation indexes. Mark task 5.1 complete, update Epic 5 progress to 1/5, and append a dated changelog entry. No ADR is needed because this implements the already-recorded provider-module ownership decision.

- [x] **Step 4: Commit the milestone task**

Run:

```bash
git add pom.xml dedup4j-storage-s3 \
  docs/architecture.md docs/implementation.md docs/taskindex.md docs/changelog.md \
  docs/epics/epic-005-s3-azure-storage-adapters/README.md \
  docs/epics/epic-005-s3-azure-storage-adapters/tasks/task-001-add-s3-storage-module.md \
  docs/superpowers/plans/2026-08-26-add-s3-storage-module.md
git commit -m "feat(storage-s3): add S3 storage module"
```

Expected: a commit containing the new module, properties tests, isolation configuration, and targeted documentation updates.
