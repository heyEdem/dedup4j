# Consumer JPA and Schema Auto-configuration Implementation Plan

> **For agentic workers:** Use subagent-driven development. Luna writes implementation and tests; the coordinating assistant plans, reviews, and verifies.

**Goal:** Execute PLAN-011 so consumers receive the complete dedup4j service graph using their existing Spring database infrastructure.

**Architecture:** Preserve framework-neutral persistence; move duplicate recovery across Spring transactions. Install prefixed metadata migrations under guarded schema modes and expose individually replaceable defaults.

**Tech Stack:** Java 21, repository-managed Spring Boot, Jakarta Persistence, Spring ORM/JDBC, Liquibase, H2, JUnit.

**Spec:** `docs/plans/2026-09-03-zero-boilerplate-spring-integration-design.md`, Workstream 2; `docs/implementation-plans/PLAN-011-consumer-jpa-and-schema-autoconfiguration.md` supplies the detailed steps and signatures.

## Global Constraints

- Implement PLAN-011 only; do not include PLAN-012 or PLAN-013.
- All production and test code is written by GPT-5.6-luna workers.
- Preserve existing user changes and use targeted documentation edits.
- Use the consumer's database; no private SQLite metadata or application logical tables.
- No resource-local transaction manipulation in production JPA code.
- Public operations use REQUIRES_NEW; duplicate recovery never replays the input stream or storage call.
- External schema mutation requires `initialize-schema=always`.
- Follow PLAN-011's task-specific no-Git-delivery instruction; leave implementation reviewable locally. Initial read-only Git inspection preceded reading that exclusion.

## Execution and Review Gates

- [x] Stage 1 — PLAN-011 Task 1 and entity mapping portion of Task 2: typed identity-race signal, injected repository constructor, prefixed mapping, and JPA regression tests. Update existing resource-local concurrency fixtures so transaction recovery belongs to their caller.
- [x] Stage 2 — remaining Task 2 and Task 3: dependency integration, versioned changelog, schema binding, guarded migration, validation, and schema/Hibernate parity tests.
- [x] Stage 3 — Tasks 4 and 5: granular conditional collaborators, common reference mapping, transactional public wrapper, override inventory and transaction/retry tests.
- [x] Stage 4 — Task 6: explicit auto-configuration ordering, minimal automatic-discovery consumer, concurrent upload proof, and reactor verification.
- [x] Stage 5 — review final implementation, resolve findings through Luna, update indexed docs and acceptance status, and report tests and changed files.

Each stage uses the exact file map and test instructions in PLAN-011. Run Maven with `-am` where reactor siblings are needed. The main assistant reviews specification compliance and code quality before accepting a stage.

## Preflight Decisions

| Related tasks | Contract / finding | Resolution |
|---|---|---|
| 1 | Existing resource-local concurrency tests expect internal retry. | Move retry into test callers while asserting production never manages transactions. |
| 2 | Entity timestamps use Instant; migration must validate against the actual ORM. | Verify real Hibernate validation and use compatible portable types. |
| 3 | Wrapping an H2 DataSource alone may not change embedded classification. | Simulate external database metadata explicitly in the policy fixture. |
| 4 | Default repository must be shared by the mutation service. | Use the new two-argument constructor. |
| 5 | Fresh retry cannot replay a consumed stream. | Reload identity and retain the winner only after rollback. |
| 6 | Imports alone do not establish Boot initialization order. | Test discovery with actual DataSource/JPA auto-configuration and explicit before/after relationships. |
| 1 → 5 | Exception identity getters are the retry interface. | Preserve the exact PLAN-011 names and types. |
| 2 → 3 | Migration/table/constraint names must match mapping. | Use PLAN-011 names; test actual schema validation. |
| 3 → 6 | Migration/validator must precede metadata use. | Prove startup ordering without consumer dedup4j infrastructure beans. |
| 4 → 5 | Only one public BlobDeduplicationService bean. | Construct raw delegate inside wrapper bean factory. |
| 4 → 6 | Added JPA dependencies affect old provider-only fixtures. | Adapt fixtures narrowly where actual Boot discovery now requires persistence. |
| 3 → consumer JPA | Boot uses nonempty EntityScanPackages instead of its default application packages. | Preserve consumer entity discovery when adding AssetContent, with regression coverage. |
| 3 → consumer migrations | Boot's Liquibase default backs off when any SpringLiquibase bean exists. | Verify application changelog coexistence; do not silently suppress a configured consumer migration. |
| 6 → local adapter | Forced concurrent uploads exposed Files.copy(REPLACE_EXISTING) throwing FileAlreadyExistsException for one deterministic key. | Publish via same-directory temporary file and atomic replacement in the local adapter, with concurrent-write and failed-write cleanup tests. Avoid service-global storage locking. |

## Progress

Planning complete. Luna workers own disjoint JPA and starter-persistence write sets; the coordinating assistant owns review and documentation. Parallel execution is safe because both use PLAN-011's fixed entity/exception contracts.

Stage 1: complete. Main-agent review confirms specification compliance and scoped code quality: the exception captures full identity, mutation no longer accesses resource-local transactions, mapping names agree with PLAN-011, and the concurrent fixture performs caller-owned rollback/retry. Luna reports 17 JPA tests passing, including the explicit no-getTransaction/no-clear test.

Stage 3: complete. Defaults back off independently across seven separate override contexts; transaction tests assert begin/delegate/rollback/begin/reload/retain/commit ordering and no one-shot input replay. Public service assembly also works with Spring Data repository registration disabled, using an internal shared EntityManager proxy when no application EntityManager bean exists. No global EntityManager bean is added.

Local adapter correction: reviewed and verified by Luna, including separate-instance concurrent writes, failed-stream preservation/cleanup, and maximum-length filenames. A short temporary-file prefix avoids filesystem name-length regressions.

2026-09-07 verification: the S3 proxy fixture now supports Object methods called by Spring's persistence postprocessor and asserts successful startup before exact-once destruction. Consumer migration import conditions now run during configuration parsing; the conventional changelog fixture is isolated so blank consumers cannot accidentally depend on it. Entity packages are appended once through a late registry postprocessor, without prematurely instantiating Spring's immutable package list.

## Final Verification — 2026-09-07

`./mvnw --batch-mode --no-transfer-progress clean verify` completed with `BUILD SUCCESS`: **168 tests, zero failures/errors/skips**, across all ten reactor modules. Test totals: core 15, JPA 17, local 15, S3 6, Azure 5, starter 79, management 5, embedded dashboard 10, standalone dashboard 16. Build finished at 01:14:10 UTC. Log: `/tmp/dedup4j-plan011-verified.log`.

Main-agent review accepted specification compliance and code quality after Luna resolved the recorded findings. No production or test edits followed this successful build. All code and tests were written by GPT-5.6-luna workers; the coordinating assistant wrote this plan, reviewed implementation, ran final verification, and updated docs.

The result remains local: no commits, branch changes, pushes, or pull requests. Existing unrelated work and local dashboard data were preserved. Legacy `blob_asset_content` data requires an application-managed transition to the new prefixed schema; the initial changelog does not copy it.

## Changed File Inventory

JPA:

```text
dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java
dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentMutationService.java
dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/DuplicateContentIdentityException.java
dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMappingTest.java
dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMutationServiceTest.java
dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ConcurrentUploadIntegrationTest.java
```

Starter production and migrations:

```text
dedup4j-spring-boot-starter/pom.xml
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jAutoConfiguration.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jProperties.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jPersistenceAutoConfiguration.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jSchemaValidator.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/SchemaInitialization.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfiguration.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/BlobReferences.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java
dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationService.java
dedup4j-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
dedup4j-spring-boot-starter/src/main/resources/db/blob-helper/db.changelog-master.yaml
dedup4j-spring-boot-starter/src/main/resources/db/blob-helper/changes/001-create-asset-content.yaml
```

Starter tests and fixtures:

```text
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/Dedup4jContextStartTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/Dedup4jPropertiesTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/ProviderAutoConfigurationDiscoveryTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jPersistenceAutoConfigurationTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfigurationTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/storage/S3BlobStorageAutoConfigurationTest.java
dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationServiceTest.java
dedup4j-spring-boot-starter/src/test/java/example/defaultpkg/DefaultEntity.java
dedup4j-spring-boot-starter/src/test/java/example/explicit/ExplicitEntity.java
dedup4j-spring-boot-starter/src/test/resources/consumer-default-changelog/db/changelog/db.changelog-master.yaml
dedup4j-spring-boot-starter/src/test/resources/db/blob-helper/consumer-test-changelog.yaml
```

Local storage and documentation:

```text
dedup4j-storage-local/src/main/java/com/edem/dedup4j/storage/local/LocalBlobStorage.java
dedup4j-storage-local/src/test/java/com/edem/dedup4j/storage/local/LocalBlobStorageIntegrationTest.java
README.md
docs/architecture.md
docs/implementation.md
docs/patterns.md
docs/decisions.md
docs/changelog.md
docs/taskindex.md
docs/implementation-plans/PLAN-011-consumer-jpa-and-schema-autoconfiguration.md
docs/plans/2026-09-06-plan011-execution.md
```
