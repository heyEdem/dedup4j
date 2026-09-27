# AssetContent Entity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a framework-independent JPA module containing a tested `AssetContent` entity for physical blob metadata.

**Architecture:** Register `dedup4j-jpa` in the Maven reactor and expose only Jakarta Persistence annotations from production code. Use Hibernate ORM and H2 in test scope to prove the mapping produces a usable schema and persists the entity correctly.

**Tech Stack:** Java 21, Maven, Jakarta Persistence 3.2, Hibernate ORM 7.4.5.Final, H2 2.4.240, JUnit Jupiter 5.13.4

**Spec:** `docs/plans/2026-08-18-asset-content-entity-design.md`

## Global Constraints

- Content identity is exactly `hash_algorithm + content_hash + size_bytes`.
- `dedup4j-core` remains free of Spring and JPA dependencies.
- The persistence table is named `blob_asset_content`.
- No repository, row-locking service, storage I/O callback, or consuming-application asset table is added in Task 2.1.

---

### Task 1: Maven module and executable mapping test

**Files:**
- Modify: `pom.xml`
- Create: `dedup4j-jpa/pom.xml`
- Create: `dedup4j-jpa/src/test/resources/META-INF/persistence.xml`
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMappingTest.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java`

**Interfaces:**
- Consumes: Jakarta Persistence annotations and lifecycle APIs.
- Produces: `AssetContent(String hashAlgorithm, String contentHash, long sizeBytes, String objectKey, String storageProvider, String bucketOrContainer, String contentType, String originalExtension)` plus read accessors for every mapped property.

- [ ] **Step 1: Add the empty Maven module boundary**

Register `<module>dedup4j-jpa</module>` after `dedup4j-core`. Create a child POM with `jakarta.persistence:jakarta.persistence-api:3.2.0` at compile scope and `org.hibernate.orm:hibernate-core:7.4.5.Final`, `com.h2database:h2:2.4.240`, and JUnit Jupiter at test scope.

- [ ] **Step 2: Configure the test persistence unit**

Create `META-INF/persistence.xml` with a resource-local `dedup4j-jpa-test` unit, `org.hibernate.jpa.HibernatePersistenceProvider`, the `AssetContent` class, H2 JDBC settings, and `hibernate.hbm2ddl.auto=create-drop`.

- [ ] **Step 3: Write the failing mapping test**

Create `AssetContentMappingTest` with:

```java
@Test
void persistsPhysicalBlobMetadataWithGeneratedState() {
    AssetContent content = new AssetContent(
            "SHA-256",
            "a".repeat(64),
            42L,
            "uploads/SHA-256/aa/" + "a".repeat(64),
            "local",
            "test-bucket",
            "application/octet-stream",
            "bin"
    );

    entityManager.getTransaction().begin();
    entityManager.persist(content);
    entityManager.getTransaction().commit();
    entityManager.clear();

    AssetContent persisted = entityManager.find(AssetContent.class, content.getId());
    assertAll(
            () -> assertNotNull(persisted.getId()),
            () -> assertEquals(1L, persisted.getRefCount()),
            () -> assertNotNull(persisted.getCreatedAt()),
            () -> assertNotNull(persisted.getUpdatedAt()),
            () -> assertNotNull(persisted.getVersion())
    );
}
```

Add focused tests using `AssetContent.class.getAnnotation(Table.class)` to assert table name `blob_asset_content`, unique constraint columns `hash_algorithm`, `content_hash`, `size_bytes`, and the three required index names/column lists. Add constructor tests for blank required strings and negative `sizeBytes`.

- [ ] **Step 4: Run the test to verify RED**

Run: `./mvnw -pl dedup4j-jpa -Dtest=AssetContentMappingTest test`

Expected: compilation fails because `AssetContent` does not exist.

- [ ] **Step 5: Implement the minimal entity mapping**

Create `AssetContent` with field-access mapping:

```java
@Entity
@Table(
        name = "blob_asset_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_blob_asset_content_identity",
                columnNames = {"hash_algorithm", "content_hash", "size_bytes"}
        ),
        indexes = {
                @Index(name = "idx_blob_asset_content_hash", columnList = "content_hash"),
                @Index(name = "idx_blob_asset_content_object_key", columnList = "object_key"),
                @Index(name = "idx_blob_asset_content_ref_count", columnList = "ref_count")
        }
)
public class AssetContent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hash_algorithm", nullable = false, updatable = false, length = 32)
    private String hashAlgorithm;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "object_key", nullable = false, updatable = false, length = 1024)
    private String objectKey;

    @Column(name = "storage_provider", nullable = false, updatable = false, length = 64)
    private String storageProvider;

    @Column(name = "bucket_or_container", nullable = false, updatable = false)
    private String bucketOrContainer;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "original_extension", length = 32)
    private String originalExtension;

    @Column(name = "ref_count", nullable = false)
    private long refCount = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
```

Add a protected no-argument constructor for JPA, the public constructor above, validation helpers, `@PrePersist`/`@PreUpdate` timestamp callbacks, and getters. On first persistence, set both timestamps to the same `Instant`; on update, change only `updatedAt`.

- [ ] **Step 6: Run the mapping test to verify GREEN**

Run: `./mvnw -pl dedup4j-jpa -Dtest=AssetContentMappingTest test`

Expected: all `AssetContentMappingTest` tests pass with zero failures and errors.

- [ ] **Step 7: Refactor and rerun the module test suite**

Remove repeated fixture values in the test only when doing so keeps expectations literal and readable. Run `./mvnw -pl dedup4j-jpa test` and keep the suite green.

### Task 2: Planning status and living codebase index

**Files:**
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/tasks/task-001-add-jpa-module-and-asset-content-entity.md`
- Modify: `docs/epics/epic-002-jpa-metadata-reference-counting/README.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/patterns.md`
- Modify: `docs/changelog.md`

**Interfaces:**
- Consumes: verified Task 2.1 implementation and test results.
- Produces: task board and indexed docs that identify Task 2.1 as complete and Task 2.2 as next.

- [ ] **Step 1: Mark Task 2.1 complete**

Change Task 2.1 status to `Complete`, mark all task steps and acceptance checks `[x]`, mark Task 2.1 complete in the Epic 2 README and task index, and update totals from 5/30 to 6/30 and Epic 2 from 0/6 to 1/6.

- [ ] **Step 2: Update only affected index sections**

Add `dedup4j-jpa` to the architecture module map and dependency list. Add the entity, POM, and mapping test entry points to implementation docs. Record the JPA entity/lifecycle/test conventions in patterns. Append this dated changelog entry:

```markdown
## 2026-08-18 — Add JPA metadata module and AssetContent entity

- Added the Jakarta Persistence module and mapped physical blob metadata with identity uniqueness, indexes, timestamps, and optimistic locking.
- Added Hibernate/H2 mapping tests and updated the Maven reactor, planning status, and living project index.
- Modules affected: root reactor, `dedup4j-jpa`, and `docs`.
```

- [ ] **Step 3: Run scoped changed-file discovery**

Run the project-required `git diff HEAD~1 --name-only`, then inspect the changed files and direct neighbors only. Also run `git status --short` so uncommitted implementation files are included in the review.

- [ ] **Step 4: Verify the complete reactor**

Run: `./mvnw --batch-mode --no-transfer-progress verify`

Expected: both modules build successfully with zero test failures or errors.

- [ ] **Step 5: Review the final diff**

Run `git diff --check`, `git status --short`, and `git diff --stat`. Confirm no Spring/JPA dependency entered `dedup4j-core` and no files outside the approved design and required documentation were changed.
