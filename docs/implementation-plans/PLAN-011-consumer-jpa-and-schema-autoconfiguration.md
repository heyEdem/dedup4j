# Consumer JPA and Schema Auto-configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dedup4j reuse the consuming Spring Boot application's database and transaction infrastructure while conditionally installing its complete metadata service graph.

**Architecture:** The JPA module remains framework-neutral about transaction ownership. The Spring Boot starter scans the dedup4j entity, builds default repositories and services, and wraps public operations in `REQUIRES_NEW` Spring transactions. A starter-owned Liquibase changelog manages only prefixed dedup4j tables under the guarded `embedded`, `always`, and `never` modes.

**Tech Stack:** Java 21, Spring Boot 3.5, Jakarta Persistence, Spring ORM/JDBC transactions, Liquibase, H2, JUnit 5, `ApplicationContextRunner`.

**Implements:** ADR-008

**Implementation status:** Complete and verified on 2026-09-07. See
[execution record](../plans/2026-09-06-plan011-execution.md) for reviewed adjustments,
the complete changed-file inventory, and verification evidence. All code and tests
were written by GPT-5.6-luna workers. Clean full-reactor verification passed
168 tests with no failures, errors, or skips. The task checklist below is the
original implementation recipe; the execution record is the completion ledger.

**Delivery:** Local working-tree changes only; no commits, pushes, or pull requests.
Initial read-only repository inspection preceded reading this plan's Git exclusion.

---

## Five-Questions Contract

- **Protected outcome (Q1):** an application with a working `DataSource`, JPA entity manager, and selected `BlobStorage` receives a usable `BlobDeduplicationService` without a `GeneralConfig` class.
- **Invariants (Q2):** one shared metadata database, conditional defaults, no resource-local transaction calls on a Spring `EntityManager`, one reference per successful call, guarded schema mutation.
- **Owner (Q3):** `dedup4j-jpa` owns persistence behavior; the starter owns Spring transactions, bean assembly, and schema policy.
- **Proof (Q4):** focused mutation, schema-mode, override, context-start, and concurrent-duplicate tests below.
- **Exclusions (Q5):** no private SQLite database, no application logical-asset tables, no cross-resource S3/database transaction claim, no Git operations.

## File Map

- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java` — use the prefixed table name.
- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentMutationService.java` — remove manual transaction restart.
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/DuplicateContentIdentityException.java`.
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMappingTest.java`.
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMutationServiceTest.java`.
- Modify: `dedup4j-spring-boot-starter/pom.xml` — add Spring Data JPA, JDBC, and Liquibase integration dependencies.
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jProperties.java` — add persistence schema mode.
- Create: `.../autoconfigure/persistence/SchemaInitialization.java`.
- Create: `.../autoconfigure/persistence/Dedup4jPersistenceAutoConfiguration.java`.
- Create: `.../autoconfigure/persistence/Dedup4jSchemaValidator.java`.
- Create: `.../autoconfigure/service/Dedup4jServiceAutoConfiguration.java`.
- Create: `.../service/SpringTransactionalBlobDeduplicationService.java`.
- Create: `.../service/BlobReferences.java` — centralize metadata-to-reference mapping.
- Create: `.../resources/db/blob-helper/db.changelog-master.yaml`.
- Create: `.../resources/db/blob-helper/changes/001-create-asset-content.yaml`.
- Modify: `.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Create: `.../test/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jPersistenceAutoConfigurationTest.java`.
- Create: `.../test/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfigurationTest.java`.
- Create: `.../test/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationServiceTest.java`.
- Create: `.../test/java/com/edem/dedup4j/autoconfigure/Dedup4jContextStartTest.java`.

## Acceptance Criteria (from Q4)

- [ ] **AssetContentMutationServiceTest.duplicateInsertEscapesForSpringRetry:** a SQL-state `23505` becomes `DuplicateContentIdentityException` without calling `EntityManager.getTransaction()`.
- [ ] **Dedup4jPersistenceAutoConfigurationTest.embeddedInitializesSchema:** default mode creates `blob_helper_asset_content` in H2.
- [ ] **Dedup4jPersistenceAutoConfigurationTest.neverDoesNotMutateSchema:** `never` leaves the database unchanged and reports the packaged migration path.
- [ ] **Dedup4jPersistenceAutoConfigurationTest.alwaysInitializesExternalDataSource:** explicit `always` runs the changelog even when the data source is not classified as embedded.
- [ ] **Dedup4jServiceAutoConfigurationTest.registersDefaults:** repository, mutation, reference-count, hasher, key strategy, metrics, and the public transactional deduplication service exist once.
- [ ] **Dedup4jServiceAutoConfigurationTest.applicationBeansWin:** each application-provided collaborator backs off only its corresponding default.
- [ ] **SpringTransactionalBlobDeduplicationServiceTest.retriesDuplicateInFreshTransaction:** a duplicate insert rolls back and retries once in a new transaction.
- [ ] **Dedup4jContextStartTest.startsWithOnlyDataSourceJpaAndProperties:** the full upload context starts with no dedup4j `@Bean` declarations.
- [ ] **Dedup4jContextStartTest.concurrentDuplicateHasOneRowAndTwoReferences:** two successful identical calls converge on one metadata row with reference count two.

## Out of Scope (from Q5)

- SQLite or a second private metadata database.
- Flyway integration; applications in `never` mode may consume the packaged SQL-equivalent changelog through their own migration process.
- XA/distributed transactions across metadata and object storage.
- Application upload/history/entity tables.
- Release/download façade additions.
- Git operations without Edem's instruction.

## Tasks

### Task 1: Make duplicate races transaction-manager neutral

**Files:**

- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/DuplicateContentIdentityException.java`
- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContentMutationService.java`
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMutationServiceTest.java`

- [ ] **Step 1: Write a failing mutation test**

Make `EntityManager.flush()` throw a `PersistenceException` whose cause is a `SQLException` with SQL state `23505`. Assert `createOrRetain` throws `DuplicateContentIdentityException` containing the candidate identity, and verify:

```java
verify(entityManager, never()).getTransaction();
verify(entityManager, never()).clear();
```

- [ ] **Step 2: Add the explicit retry signal**

```java
public final class DuplicateContentIdentityException extends RuntimeException {

    private final String hashAlgorithm;
    private final String contentHash;
    private final long sizeBytes;

    public DuplicateContentIdentityException(AssetContent candidate, Throwable cause) {
        super("Concurrent insert for content identity "
                + candidate.getHashAlgorithm() + ":" + candidate.getContentHash()
                + ":" + candidate.getSizeBytes(), cause);
        this.hashAlgorithm = candidate.getHashAlgorithm();
        this.contentHash = candidate.getContentHash();
        this.sizeBytes = candidate.getSizeBytes();
    }

    public String getHashAlgorithm() { return hashAlgorithm; }
    public String getContentHash() { return contentHash; }
    public long getSizeBytes() { return sizeBytes; }
}
```

In `AssetContentMutationService.insertOrRetry`, keep `persist` plus `flush`, but replace `restartTransactionAfterFailedInsert()` and the in-method reload with:

```java
} catch (PersistenceException failure) {
    if (isDuplicateKeyFailure(failure)) {
        throw new DuplicateContentIdentityException(candidate, failure);
    }
    throw failure;
}
```

Delete the `EntityTransaction` import and `restartTransactionAfterFailedInsert` method. Update the class Javadoc to state that the Spring integration retries the operation after rollback.

Add a second constructor so starter-level repository overrides propagate into mutation behavior:

```java
public AssetContentMutationService(
        EntityManager entityManager,
        AssetContentRepository repository
) {
    this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
}
```

Keep the existing one-argument constructor as a convenience that delegates to this constructor with `new AssetContentRepository(entityManager)`.

- [ ] **Step 3: Run the JPA mutation tests**

```bash
./mvnw -pl dedup4j-jpa test -Dtest=AssetContentMutationServiceTest
```

Expected: ordinary insert/retain tests and the transaction-neutral duplicate signal pass.

### Task 2: Establish the prefixed, versioned schema contract

**Files:**

- Modify: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java`
- Modify: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMappingTest.java`
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/blob-helper/db.changelog-master.yaml`
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/blob-helper/changes/001-create-asset-content.yaml`

- [ ] **Step 1: Change and test the table name**

Use the exact table and constraint/index names:

```java
@Table(
        name = "blob_helper_asset_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_blob_helper_asset_content_identity",
                columnNames = {"hash_algorithm", "content_hash", "size_bytes"}
        ),
        indexes = {
                @Index(name = "idx_blob_helper_asset_content_hash", columnList = "content_hash"),
                @Index(name = "idx_blob_helper_asset_content_object_key", columnList = "object_key"),
                @Index(name = "idx_blob_helper_asset_content_ref_count", columnList = "ref_count")
        }
)
```

- [ ] **Step 2: Add a Liquibase master changelog**

```yaml
databaseChangeLog:
  - include:
      file: db/blob-helper/changes/001-create-asset-content.yaml
```

The `001` changeset must create `blob_helper_asset_content` with columns matching every `AssetContent` mapping, the three indexes above, and the unique identity constraint. Use database-neutral Liquibase types: `UUID`, `VARCHAR(32)`, `VARCHAR(128)`, `BIGINT`, `VARCHAR(1024)`, `VARCHAR(64)`, `VARCHAR(255)`, `TIMESTAMP`, and `BIGINT` for the optimistic-lock version. Default `ref_count` to `1`; do not default content identity or location fields.

- [ ] **Step 3: Run mapping and migration parity tests**

Extend `AssetContentMappingTest` to assert the exact table/constraint/index names. Add a test that runs the changelog against H2, boots Hibernate with `hibernate.hbm2ddl.auto=validate`, and obtains an `EntityManagerFactory` successfully.

```bash
./mvnw -pl dedup4j-jpa,dedup4j-spring-boot-starter -am test -Dtest=AssetContentMappingTest,Dedup4jPersistenceAutoConfigurationTest
```

Expected: entity mapping and packaged schema agree.

### Task 3: Bind guarded schema initialization modes

**Files:**

- Modify: `dedup4j-spring-boot-starter/pom.xml`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/SchemaInitialization.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jProperties.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jSchemaValidator.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jPersistenceAutoConfiguration.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/persistence/Dedup4jPersistenceAutoConfigurationTest.java`

- [ ] **Step 1: Add Spring persistence dependencies**

Add `spring-boot-starter-data-jpa`, `spring-jdbc`, and `liquibase-core` as compile dependencies. Keep H2 test-scoped.

- [ ] **Step 2: Add schema properties**

```java
public enum SchemaInitialization {
    EMBEDDED,
    ALWAYS,
    NEVER
}
```

Add `Persistence` to `Dedup4jProperties` with:

```java
private SchemaInitialization initializeSchema = SchemaInitialization.EMBEDDED;
```

Bind from `dedup4j.persistence.initialize-schema` and test all three relaxed-binding values.

- [ ] **Step 3: Implement schema initialization policy**

Annotate `Dedup4jPersistenceAutoConfiguration` with `@AutoConfiguration` and `@EntityScan(basePackageClasses = AssetContent.class)`. Register a named `SpringLiquibase dedup4jLiquibase` and compute its `shouldRun` value as:

```java
mode == SchemaInitialization.ALWAYS
        || (mode == SchemaInitialization.EMBEDDED
        && EmbeddedDatabaseConnection.isEmbedded(dataSource))
```

Configure it exactly with:

```java
liquibase.setDataSource(dataSource);
liquibase.setChangeLog("classpath:db/blob-helper/db.changelog-master.yaml");
liquibase.setDatabaseChangeLogTable("BLOB_HELPER_DATABASE_CHANGELOG");
liquibase.setDatabaseChangeLogLockTable("BLOB_HELPER_DATABASE_CHANGELOG_LOCK");
liquibase.setShouldRun(shouldInitialize);
```

`shouldInitialize` must be false in `NEVER`, and false in `EMBEDDED` with a non-embedded data source. Keeping one named no-op bean in those modes gives the schema validator a deterministic initialization dependency without mutating the database.

- [ ] **Step 4: Validate the schema after optional initialization**

`Dedup4jSchemaValidator` receives a `JdbcTemplate` and calls:

```sql
select hash_algorithm, content_hash, size_bytes, storage_provider,
       bucket_or_container, object_key, ref_count
from blob_helper_asset_content
where 1 = 0
```

Convert `DataAccessException` into an `IllegalStateException` that names the missing table, the active mode, and `classpath:db/blob-helper/db.changelog-master.yaml`. The validator must depend on `dedup4jLiquibase` when that bean exists.

- [ ] **Step 5: Test all schema modes**

Use H2 for `EMBEDDED`; wrap an H2 `DataSource` so Spring does not classify it as embedded for the `ALWAYS` and external-`EMBEDDED` cases. Assert:

- default `EMBEDDED` creates and validates the table;
- `ALWAYS` creates it through the wrapped data source;
- `NEVER` never creates it and fails with migration guidance when absent;
- external `EMBEDDED` behaves like `NEVER`;
- `NEVER` succeeds when the application pre-creates the schema.

Run:

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=Dedup4jPersistenceAutoConfigurationTest,Dedup4jPropertiesTest
```

### Task 4: Auto-register the default persistence and domain collaborators

**Files:**

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfiguration.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfigurationTest.java`

- [ ] **Step 1: Write an application-context bean inventory test**

With `EntityManager`, `PlatformTransactionManager`, and a test `BlobStorage`, assert single beans of:

```text
AssetContentRepository
AssetContentMutationService
ReferenceCountService
ContentHasher
ObjectKeyStrategy
Dedup4jMetrics
BlobDeduplicationService
```

For each public collaborator type, run a separate context with an application bean of that type and assert the exact instance is retained.

- [ ] **Step 2: Implement conditional defaults**

Register these exact defaults with `@ConditionalOnMissingBean` on each method:

```java
new AssetContentRepository(entityManager)
new AssetContentMutationService(entityManager, repository)
new ReferenceCountService(repository, blobStorage)
new Sha256ContentHasher()
new HashObjectKeyStrategy(properties.getStorage().getKeyPrefix())
new Dedup4jMetrics(meterRegistryProvider.getIfAvailable())
```

Do not register `DefaultBlobDeduplicationService` separately. Task 5 constructs it as an internal delegate and exposes only its transactional decorator, preventing two `BlobDeduplicationService` candidates.

- [ ] **Step 3: Run the bean graph test**

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=Dedup4jServiceAutoConfigurationTest
```

Expected: defaults assemble once and granular application overrides win.

### Task 5: Put public operations and duplicate retry on Spring transactions

**Files:**

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationService.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationServiceTest.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfiguration.java`

- [ ] **Step 1: Test transaction boundaries before implementation**

Use a recording `PlatformTransactionManager`. Assert each `store`, `retain`, `release`, and `get` call uses `PROPAGATION_REQUIRES_NEW`. Make the delegate throw `DuplicateContentIdentityException`; assert the wrapper starts a second transaction, reloads the winning identity, retains it once, and returns a duplicate `BlobReference`. Verify the delegate is called once and no stream replay occurs. If the winning row is absent, assert the original race exception is propagated.

- [ ] **Step 2: Implement the transactional decorator**

```java
public final class SpringTransactionalBlobDeduplicationService
        implements BlobDeduplicationService {

    private final BlobDeduplicationService delegate;
    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate transactions;

    public SpringTransactionalBlobDeduplicationService(
            BlobDeduplicationService delegate,
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.repository = Objects.requireNonNull(repository);
        this.referenceCountService = Objects.requireNonNull(referenceCountService);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public BlobReference store(StoreBlobCommand command) {
        try {
            return requiredResult(() -> delegate.store(command));
        } catch (DuplicateContentIdentityException race) {
            return requiredResult(() -> retainWinner(race));
        }
    }

    private BlobReference retainWinner(DuplicateContentIdentityException race) {
        AssetContent winner = repository.findByIdentity(
                        race.getHashAlgorithm(), race.getContentHash(), race.getSizeBytes()
                )
                .orElseThrow(() -> race);
        referenceCountService.retain(winner.getId());
        return BlobReferences.from(winner, true);
    }

    private <T> T requiredResult(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(status -> action.get()));
    }

    // retain/release use transactions.executeWithoutResult; get uses requiredResult.
}
```

Extract the existing `AssetContent`-to-`BlobReference` construction into a package-private `BlobReferences.from(AssetContent, boolean)` helper used by both `DefaultBlobDeduplicationService` and this decorator. The retry starts only after the failed transaction rolls back, reloads the row that won the unique-key race, and retains it. It must not replay `StoreBlobCommand.content()` because that stream is one-shot, and it must not call physical storage again.

- [ ] **Step 3: Register the public wrapper**

Expose one bean with `@ConditionalOnMissingBean(BlobDeduplicationService.class)`. Inside that bean method, construct `DefaultBlobDeduplicationService` from the conditional collaborators and immediately wrap it in `SpringTransactionalBlobDeduplicationService`. Return the wrapper as `BlobDeduplicationService`; the raw delegate is not a bean. This lets an application replace the entire service cleanly and avoids `@Transactional` self-invocation.

- [ ] **Step 4: Run service transaction tests**

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=SpringTransactionalBlobDeduplicationServiceTest,Dedup4jServiceAutoConfigurationTest
```

### Task 6: Register ordering and prove zero-boilerplate startup

**Files:**

- Modify: `dedup4j-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/Dedup4jContextStartTest.java`

- [ ] **Step 1: Register persistence and service configurations**

Use this effective order:

```text
provider storage auto-configurations
Dedup4jPersistenceAutoConfiguration
Dedup4jServiceAutoConfiguration
Dedup4jAutoConfiguration
```

Use `before`/`after` attributes on the auto-configurations as well as import order so ordering is explicit.

- [ ] **Step 2: Create a minimal context fixture**

The fixture supplies only a Boot-managed H2 data source/JPA setup plus:

```text
dedup4j.storage.provider=local
dedup4j.storage.local.root-directory=<JUnit temp directory>
```

It must not declare a dedup4j repository, hasher, key strategy, service, transaction wrapper, entity scan, or schema bean.

- [ ] **Step 3: Prove startup and concurrent deduplication**

Assert the context starts, the prefixed table exists, and the public service is callable. Launch two identical uploads behind a barrier; await both results and assert:

```text
physical object count = 1
blob_helper_asset_content row count = 1
ref_count = 2
both results share assetContentId and objectKey
exactly one result is duplicate after the race settles
```

- [ ] **Step 4: Run starter and JPA verification**

```bash
./mvnw --batch-mode --no-transfer-progress -pl dedup4j-jpa,dedup4j-spring-boot-starter -am verify
```

Expected: `BUILD SUCCESS`, including schema and context-start coverage.

- [ ] **Step 5: Report changed files to Edem**

Do not run Git commands. Provide the path list and test results for Edem's Git workflow.

## Definition of Done

- [ ] A normal application declares no dedup4j infrastructure beans.
- [ ] dedup4j uses the application's managed JPA and transaction infrastructure.
- [ ] No production-like database is mutated unless `initialize-schema=always`.
- [ ] Missing schema errors identify the exact packaged migration.
- [ ] Concurrent identical successful calls produce one row and correct references.
- [ ] Default beans back off independently.
- [ ] Full JPA/starter verification passes.
- [ ] No Git operation was performed without explicit permission.
