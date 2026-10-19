# Persistent Upload History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dedup4j's upload totals all-time and restart-proof per application dataset, count only completed uploads, and persist and surface failures on every dashboard.

**Architecture:**

- Three append-only tables (dataset identity, upload ledger, failure log) live in the
  consumer's database. They are created by a separate history Liquibase changelog and
  guarded by database triggers.
- The transactional service wrapper appends one ledger row per completed upload under a
  row lock on the dataset row. Micrometer updates after commit.
- A failure is appended in its own transaction.
- Management and the embedded dashboard read totals and failures from the ledger.
- The standalone dashboard keys totals, trends and cached failures by `datasetId`.

**Tech Stack:**

- Java 21, Spring Boot 4.1.1, Hibernate 7, Liquibase
- H2 2.4.240, PostgreSQL via Testcontainers
- SQLite (standalone dashboard), JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-19-persistent-upload-history-design.md`, which
takes precedence over this plan on any conflict.

## Global Constraints

- `db/dedup4j/changes/001-create-asset-content.yaml` must not change.
- New tables use the `dedup4j_` prefix.
- All three history tables are append-only, enforced in three places:
  - DB triggers reject `UPDATE` and `DELETE`
  - `TRUNCATE` is rejected (a PostgreSQL statement trigger; an H2 FK reference)
  - entities use JPA-standard immutability, and repositories expose only append and read
    methods
- Entity immutability: `@Column(updatable = false)` on every column, no setters, and a
  `protected` no-arg constructor. No Hibernate annotations: `dedup4j-jpa` stays
  provider-neutral (Jakarta Persistence API only).
- Failures are paged only by their gapless `sequence_no`, ascending. Never page by
  timestamp.
- "Storage avoided" (`avoidedBytes`) means capacity not consumed by an additional copy, as
  defined in the spec's Terms table. Every UI that shows it uses the label "Storage
  avoided" with that definition as help text.
- No method anywhere updates or deletes history rows. Corrections are compensating rows
  only.
- History runs only on PostgreSQL and H2. Any other database fails startup while
  `dedup4j.history.enabled=true`, which is the default.
- Totals start at zero. No backfill from `dedup4j_asset_content`.
- Micrometer meter names stay unchanged. Meters increment only after a successful commit.
- PostgreSQL Testcontainers tests are required. OrbStack/Docker must be running to
  execute them. Do not mark them `disabledWithoutDocker`.
- Commit messages are written as Edem's. No `Co-Authored-By` or "Generated with" lines.
- Work on a new branch `feat/persistent-upload-history`. The checkout is currently on
  `epic-006-structured-operational-logging`; ask Edem which base to branch from before
  Task 1.
- After the last task, follow the repo `CLAUDE.md` "After Every Feature" doc steps
  (Task 9).

## Review Focus

1. **A failure that commits late.** Failure A is recorded first but its transaction stalls,
   while failure B commits and is collected. Expected: A is still collected on a later
   page, because sequence numbers are assigned and committed under the dataset lock (Task 2
   test, repeated on PostgreSQL in Task 3).
2. **Existing production schema with `initialize-schema: never`.** An app upgrading
   without running the history changelog must fail at startup, with a message naming
   `classpath:db/dedup4j/history/db.changelog-history.yaml`. It must not fail on the first
   upload instead (Task 4 test).
3. **Recording the failure itself fails** (database down). The caller must still receive
   the original exception, unchanged and with the same instance (Task 5 test).
4. **Exceptions with a `null` or huge message.** Store the exception's simple class name
   when the message is null, and truncate to 1000 characters (Task 5 test).
5. **Standalone dashboard polling an older instance** whose `/metrics` JSON has no
   `datasetId`, `physicalWrites` or `totalsScope`, and that has no paging on `/failures`.
   Treat it as its own dataset keyed by instance id, with no exception and no double
   counting (Task 8 test).

---

### Task 1: History schema, immutable entities, and H2 enforcement

**Files:**
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/dedup4j/history/db.changelog-history.yaml`
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/dedup4j/history/changes/001-create-history-tables.yaml`
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/dedup4j/history/changes/002-append-only-postgresql.yaml`
- Create: `dedup4j-spring-boot-starter/src/main/resources/db/dedup4j/history/changes/003-append-only-h2.yaml`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/HistoryDataset.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/UploadEvent.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/FailureEvent.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/H2AppendOnlyTrigger.java`
- Modify: `dedup4j-spring-boot-starter/pom.xml` (H2 becomes `<optional>true</optional>`
  compile scope, for `org.h2.api.Trigger`. The starter already integrates providers, so
  `dedup4j-jpa` keeps its boundary.)
- Test: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/history/HistorySchemaH2Test.java`

The package is `com.edem.dedup4j.historystore`, deliberately **not** under
`com.edem.dedup4j.jpa`. The persistence auto-configuration registers `com.edem.dedup4j.jpa`
for entity scanning, and these entities must be registered only when history is enabled
(Task 4).

**Interfaces:**
- Produces:
  - **Tables:** columns exactly as in the spec's "Tables" section.
    - `dedup4j_history_dataset`: `id SMALLINT PK CHECK (id = 1)`
    - `dedup4j_upload_event`: PK `sequence_no BIGINT`
    - `dedup4j_failure_event`: PK `sequence_no BIGINT`, plus `id UUID` with a unique
      constraint
    - FKs as in the spec. The self-references `compensates_sequence_no` and
      `compensates_failure_id` are what block `TRUNCATE` on H2.
  - **Indexes:**
    - `dedup4j_failure_event (recorded_at, id)`
    - `dedup4j_upload_event (asset_content_id)`
  - **`HistoryDataset`:** `getId(): short`, `getDatasetId(): UUID`, `getCreatedAt(): Instant`.
  - **`UploadEvent`:** constructor taking every column except nothing generated, so
    `sequence_no` is passed in. Getters for all columns.
  - **`FailureEvent`:** constructor `(long sequenceNo, UUID id, UUID datasetId,
    String instanceId, Instant recordedAt, String operation, String contentHash,
    String objectKey, String message, UUID compensatesFailureId)` and getters.
  - **`H2AppendOnlyTrigger implements org.h2.api.Trigger`:** `fire(...)` always throws
    `SQLException("dedup4j history tables are append-only")`.

- [ ] **Step 1: Write the failing test.** `HistorySchemaH2Test` runs only the history
  changelog plus `001-create-asset-content` against `jdbc:h2:mem` using Liquibase. Then:
  - `datasetRowExistsExactlyOnce()`: `select count(*) from dedup4j_history_dataset` is
    `1`, and `dataset_id` is not null.
  - `secondDatasetRowRejected()`: `insert … values (2, …)` throws.
  - `@ParameterizedTest` over the three tables:
    - `updateRejected(table)`: after seeding one row via plain JDBC insert, `update <table>
      set …` throws `DataAccessException`. The message contains "append-only".
    - `deleteRejected(table)`: likewise.
    - `truncateRejected(table)`: `truncate table <table>` throws.
  - `insertAllowed()`: inserting an upload event and a failure event succeeds.
- [ ] **Step 2: Run it and confirm it fails.**
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test -Dtest=HistorySchemaH2Test
  -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL, because the changelog file
  does not exist.
- [ ] **Step 3: Write the changelogs.**
  - **`001`:** creates the three tables.
    - Insert the dataset row with `valueComputed`: `gen_random_uuid()` when
      `dbms: postgresql`, `RANDOM_UUID()` when `dbms: h2`.
    - Use the timezone-aware timestamp type matching `001-create-asset-content`.
  - **`002` (`dbms: postgresql`):**
    - one plpgsql function raising
      `EXCEPTION 'dedup4j history tables are append-only'`
    - per table, `BEFORE UPDATE OR DELETE … FOR EACH ROW` and `BEFORE TRUNCATE … FOR EACH
      STATEMENT` triggers
  - **`003` (`dbms: h2`):** per table,
    `CREATE TRIGGER … BEFORE UPDATE, DELETE ON … FOR EACH ROW CALL
    'com.edem.dedup4j.history.H2AppendOnlyTrigger'`.
- [ ] **Step 4: Write the three entities and the trigger class.**
  - Entities follow the Global Constraints immutability rule, with `@Table` names exactly
    as the tables.
  - `UploadEvent`'s `@Id` is `sequenceNo`, assigned by the caller, with no generator.
- [ ] **Step 5: Run the test and confirm it passes.** Same command. Expected: PASS.
- [ ] **Step 6: Commit.** `feat(history): add append-only history schema and entities`

### Task 2: Ledger and failure-log repositories (insert-only)

**Files:**
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/UploadLedgerRepository.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/FailureLogRepository.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/UploadContribution.java`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/historystore/LedgerTotals.java`
- Test: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/history/UploadLedgerRepositoryH2Test.java`

**Interfaces:**
- Produces:
  - `record UploadContribution(UUID assetContentId, String hashAlgorithm,
    String contentHash, long sizeBytes, int uploadDelta, int duplicateDelta,
    int physicalWriteDelta, long logicalBytesDelta, long avoidedBytesDelta,
    Long compensatesSequenceNo)`
    - `static UploadContribution completed(UUID assetContentId, String hashAlgorithm,
      String contentHash, long sizeBytes, boolean duplicate, boolean physicalWrite)` sets:
      - upload +1
      - duplicate +1 or 0
      - physical write +1 or 0
      - logical bytes `sizeBytes`
      - avoided bytes `sizeBytes` if duplicate, else 0
    - `UploadContribution compensating(UploadEvent original)` negates every delta and sets
      `compensatesSequenceNo`.
  - `record LedgerTotals(long uploads, long duplicates, long physicalWrites,
    long logicalBytes, long avoidedBytes)` with `static LedgerTotals ZERO`.
  - `final class UploadLedgerRepository(EntityManager)`:
    - `UploadEvent append(UploadContribution c, String instanceId, Instant recordedAt)`
      runs in the caller's transaction:
      1. `find(HistoryDataset.class, (short) 1, PESSIMISTIC_WRITE)`
      2. read the highest-`sequence_no` row
      3. persist row N+1 with totals = previous + deltas
      4. `flush()`
    - `LedgerTotals latestTotals()`
    - `UUID datasetId()`
  - `final class FailureLogRepository(EntityManager)`:
    - `FailureEvent append(String instanceId, Instant recordedAt, String operation,
      String contentHash, String objectKey, String message)`. Like the ledger, it locks the
      dataset row with `PESSIMISTIC_WRITE`, assigns `sequence_no` = highest + 1, and
      generates the id with `UUID.randomUUID()`.
    - `List<FailureEvent> pageAfter(long afterSequenceNo, int limit)`: ascending
      `sequence_no`, where `afterSequenceNo = 0` starts from the beginning.
    - `List<FailureEvent> recentSince(Instant since, int limit)`: descending
      `recorded_at`, for display only.

- [ ] **Step 1: Write the failing tests.** These use the Task 1 H2 harness plus a Hibernate
  `EntityManagerFactory` over `com.edem.dedup4j.historystore`.
  - **`emptyLedgerIsZero()`:** `latestTotals()` equals `LedgerTotals.ZERO`.
  - **`totalsAccumulate()`:** append three completed uploads:
    - `(new, physicalWrite=true, 100 B)`
    - `(duplicate, physicalWrite=false, 100 B)`
    - `(duplicate, physicalWrite=true, 50 B)`

    Then totals equal `(3, 2, 2, 250, 150)`, and sequence numbers are `1, 2, 3`.
  - **`compensationCancelsOriginal()`:** append `(new, 100 B)`, then
    `compensating(original)`. Totals equal `ZERO`, and the compensating row's
    `compensatesSequenceNo` equals `1`.
  - **`delayedCommitIsNotSkipped()`:**
    1. Thread A begins a transaction, appends, and sleeps 500 ms before commit.
    2. Thread B starts 100 ms later, appends, and commits.
    3. After both commit, sequence numbers are `{1, 2}`, `latestTotals().uploads()` is `2`,
       and B's row has `total_uploads = 2`, which proves it waited for A.
  - **`failurePagingReturnsEachRowOnce()`:** append 5 failures. Page with `limit = 2`
    following the sequence cursor, and expect sequences `1..5` in ascending order, then an
    empty page.
  - **`delayedFailureCommitIsNotSkipped()`:**
    1. Thread A begins a transaction, appends a failure, and sleeps 500 ms before commit.
    2. Thread B starts 100 ms later, appends a failure, and commits.
    3. A reader that paged after B's commit and saved B's sequence as its cursor must still
       receive A on its next `pageAfter`. Because B waited for A, A's sequence is `1` and
       B's is `2`, so the reader's first page already contains both, in order.
- [ ] **Step 2: Run them and confirm they fail.**
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test -Dtest=UploadLedgerRepositoryH2Test
  -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL, because of the missing classes.
- [ ] **Step 3: Implement the four types** with the signatures above.
- [ ] **Step 4: Run them and confirm they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(history): add insert-only ledger and failure log repositories`

### Task 3: PostgreSQL enforcement and ledger tests (Testcontainers)

**Files:**
- Modify: `dedup4j-spring-boot-starter/pom.xml`, adding at test scope, with versions
  managed by the Spring Boot BOM:
  - `org.testcontainers:testcontainers-postgresql`
  - `org.testcontainers:testcontainers-junit-jupiter`
  - `org.postgresql:postgresql`
- Test: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/history/HistoryPostgresTest.java`

**Interfaces:** consumes Tasks 1 and 2 unchanged.

- [ ] **Step 1: Write the test** with `@Testcontainers`, using
  `PostgreSQLContainer("postgres:16-alpine")`. Run the same Liquibase changelogs, then
  repeat:
  - Task 1's parameterized `updateRejected`, `deleteRejected` and `truncateRejected`
  - `datasetRowExistsExactlyOnce`
  - Task 2's `delayedCommitIsNotSkipped`, `delayedFailureCommitIsNotSkipped` and
    `failurePagingReturnsEachRowOnce`
- [ ] **Step 2: Run the test.** First start OrbStack (`open -a OrbStack`), then
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test -Dtest=HistoryPostgresTest
  -Dsurefire.failIfNoSpecifiedTests=false`. Expected: PASS. Any failure here is a real
  defect in Task 1 or 2, so fix it there.
- [ ] **Step 3: Commit.** `test(history): enforce append-only history on PostgreSQL`

### Task 4: History service, configuration, startup guard and schema validation

**Files:**
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/UploadHistory.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/JpaUploadHistory.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/DisabledUploadHistory.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/HistoryTotals.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/history/FailureRecord.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/history/Dedup4jHistoryAutoConfiguration.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/history/HistoryDatabaseGuard.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jProperties.java` (nested `History { boolean enabled = true; }`)
- Modify: `…/autoconfigure/persistence/Dedup4jPersistenceAutoConfiguration.java` (when history is enabled, also register the `com.edem.dedup4j.historystore` entity package)
- Modify: `…/autoconfigure/persistence/Dedup4jSchemaValidator.java` (when history is enabled, also probe the three history tables)
- Modify: `dedup4j-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/history/Dedup4jHistoryAutoConfigurationTest.java`

**Interfaces:**
- Produces:
  - **`HistoryTotals`:**
    `record HistoryTotals(String scope, String datasetId, long uploads, long duplicates,
    long physicalWrites, long logicalBytes, long avoidedBytes)`
    - `scope` is `"all-time"` or `"since-start"`.
    - `newUploads()` returns uploads − duplicates.
    - `skippedPhysicalWrites()` returns uploads − physicalWrites.
  - **`FailureRecord`:**
    `record FailureRecord(long sequenceNo, UUID id, Instant recordedAt, String operation,
    String message, String instanceId, String contentHash, String objectKey)`.
  - **`UploadHistory`:**
    - `void recordUpload(UploadContribution c)`: inside the caller's transaction.
    - `void recordFailure(String operation, String contentHash, String objectKey,
      Throwable failure)`: in its own `REQUIRES_NEW` transaction.
    - `HistoryTotals totals()`
    - `List<FailureRecord> recentFailures(Instant since, int limit)`: descending, for
      display.
    - `List<FailureRecord> failuresAfter(long afterSequenceNo, int limit)`: ascending, for
      ingestion.
    - `boolean persistent()`
  - **`JpaUploadHistory`:** built from the two repositories, the instance id, a
    `PlatformTransactionManager` and a `Clock`.
    - Truncates `recordedAt` to `MICROS`.
    - Failure message: the class simple name if null, truncated to 1000 characters.
  - **`DisabledUploadHistory`:** no-op writes, empty failures, `persistent() = false`.
    - `totals()` reads Micrometer counters with scope `"since-start"`. Its constructor
      takes `ObjectProvider<MeterRegistry>`.
  - **Beans:**
    - `UploadHistory uploadHistory(…)`, `@ConditionalOnMissingBean`
    - `JpaUploadHistory` when `dedup4j.history.enabled=true` (`matchIfMissing`), otherwise
      `DisabledUploadHistory`
    - `SpringLiquibase dedup4jHistoryLiquibase`:
      - changelog `classpath:db/dedup4j/history/db.changelog-history.yaml`
      - same tracking tables as `dedup4jLiquibase`
      - same `initialize-schema` gating
      - `@DependsOn("dedup4jLiquibase")`
  - **Instance id:** the property `dedup4j.history.instance-id`. It defaults to
    `${dedup4j.management.instance-id:}`, falling back to the host name.

- [ ] **Step 1: Write the failing tests** with an `ApplicationContextRunner` on H2.
  - `historyEnabledByDefaultUsesJpa()`: the `UploadHistory` bean is a `JpaUploadHistory`,
    `persistent()` is true, and `totals().scope()` is `"all-time"`.
  - `historyDisabledUsesCountersAndSkipsChangelog()`: with `dedup4j.history.enabled=false`,
    the bean is a `DisabledUploadHistory` and the table `dedup4j_upload_event` does not
    exist.
  - `unsupportedDatabaseFailsStartup()`: a `DataSource` whose
    `DatabaseMetaData.getDatabaseProductName()` returns `"MySQL"` (Mockito). The context
    fails, and the message contains `"dedup4j history requires PostgreSQL or H2"` and
    `"dedup4j.history.enabled=false"`.
  - `missingHistoryTablesFailStartupWithGuidance()` (**Review Focus 2**): use
    `initialize-schema=never` on an H2 database that has only `001-create-asset-content`
    applied. The context fails, and the message contains
    `"classpath:db/dedup4j/history/db.changelog-history.yaml"`.
  - `failureMessageNullAndLongHandled()` (**Review Focus 4**): recording a
    `RuntimeException` with a null message stores `"RuntimeException"`, and a
    5000-character message is stored at 1000 characters.
  - `recordedAtTruncatedToMicros()`: use a fixed `Clock` at `…T00:00:00.123456789Z`. The
    stored `recordedAt` is `….123456Z`, so values match across H2 and PostgreSQL.
- [ ] **Step 2: Run them and confirm they fail.**
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test
  -Dtest=Dedup4jHistoryAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: FAIL.
- [ ] **Step 3: Implement the types, properties, guard, validator extension and
  registration** listed above. The guard is an `InitializingBean` that checks
  `getDatabaseProductName()` for `PostgreSQL` or `H2`.
- [ ] **Step 4: Run them, then the whole module.**
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test`. Expected: all PASS, including the
  existing `Dedup4jPersistenceAutoConfigurationTest`.
- [ ] **Step 5: Commit.** `feat(history): add upload history service and configuration`

### Task 5: Write path: record outcomes in the transactional wrapper

**Files:**
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/StoreOutcome.java`
- Modify: `…/service/DefaultBlobDeduplicationService.java`
  - Add `storeWithOutcome`; `store` delegates to it.
  - Remove both `metrics.recordUpload` calls.
  - `release` is otherwise unchanged; it keeps `recordDeleteFailure` and its log line.
    Delete failures are recorded by the wrapper.
- Modify: `…/service/SpringTransactionalBlobDeduplicationService.java`
- Modify: `…/observability/Dedup4jMetrics.java`
  - `recordUpload(long bytes, boolean duplicate, boolean physicalWrite)` replaces the
    two-argument method.
  - `skipped.physical.writes` increments only when `!physicalWrite`.
- Modify: `…/autoconfigure/service/Dedup4jServiceAutoConfiguration.java` (pass
  `UploadHistory`, `Dedup4jMetrics` and the instance id to both services)
- Test: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/UploadHistoryRecordingTest.java`
- Modify tests: `SpringTransactionalBlobDeduplicationServiceTest`, `DefaultBlobDeduplicationServiceTest` (new constructor arguments; remove expectations that meters change inside `store`)

**Interfaces:**
- Consumes: `UploadHistory`, `UploadContribution.completed(...)` from Tasks 2 and 4.
- Produces:
  - `record StoreOutcome(BlobReference reference, boolean physicalWrite)`
    - `physicalWrite` is true when `storeNewContent` called `storage.put`, including when
      `createOrRetain` returned an existing row.
  - `public StoreOutcome storeWithOutcome(StoreBlobCommand)` on
    `DefaultBlobDeduplicationService`.
  - New wrapper constructor `(BlobDeduplicationService delegate, AssetContentRepository,
    ReferenceCountService, PlatformTransactionManager, UploadHistory, Dedup4jMetrics)`.
    - The old four-argument constructor delegates to it with `DisabledUploadHistory` and
      `new Dedup4jMetrics(null)`.

**Wrapper `store` algorithm.** The test does not determine this, so it is fixed here:

```text
try:
  tx(REQUIRES_NEW):
    outcome = delegate is DefaultBlobDeduplicationService d
              ? d.storeWithOutcome(cmd)
              : new StoreOutcome(delegate.store(cmd), !ref.duplicate())
    history.recordUpload(completed(outcome))
    registerAfterCommit(() -> metrics.recordUpload(size, outcome.duplicate, outcome.physicalWrite))
catch DuplicateContentIdentityException race:
  tx(REQUIRES_NEW): retainWinner(race)
                    → recordUpload(completed(dup=true, physicalWrite=true))
                    + afterCommit meters
  (on failure of this branch: go to the catch below with hash = race.getContentHash())
catch RuntimeException failure:
  recordGuarded("upload", hashOrNull, objectKeyOrNull, failure)
  throw failure   // the same instance

release(id):
  try: tx(REQUIRES_NEW): delegate.release(id)
  catch RuntimeException failure:
    if failure is BlobStorageException: recordGuarded("delete", null, objectKeyFrom(failure), failure)
    throw failure   // the same instance

recordGuarded(op, hash, key, failure):   // runs after the failed transaction has ended
  try: history.recordFailure(op, hash, key, failure)
  catch RuntimeException recordingFailure: log at WARN; failure.addSuppressed(recordingFailure)
```

- [ ] **Step 1: Write the failing tests.** Use H2 with the full starter context, local
  storage in a temp dir, and a `SimpleMeterRegistry`.
  - `newUploadRecordedOnce()`: one store gives totals `(1, 0, 1, n, 0)` and meter
    `dedup4j.uploads == 1`.
  - `duplicateUploadRecorded()`: storing the same bytes twice gives totals
    `(2, 1, 1, 2n, n)`.
  - `totalsSurviveContextRestart()`: store in context 1, close it, open context 2 on the
    same H2 file DB. `totals().uploads()` is `1`.
  - `failedStorageWriteExcludedFromTotalsAndListed()`: the storage stub throws
    `BlobStorageException` on `put`. The caller gets that exception, totals stay `ZERO`,
    meter `dedup4j.uploads == 0`, and `recentFailures(EPOCH, 10)` has one record with
    operation `"upload"`.
  - `duplicateRaceCountedOnceAsPhysicalWrite()`: the stub mutation service throws
    `DuplicateContentIdentityException` once, after storage put. Totals are
    `(1, 1, 1, n, n)` and there are 0 failures.
  - `commitFailureLeavesMetersUnchanged()`: a transaction manager whose `commit` throws.
    Meter `dedup4j.uploads == 0` and the ledger is empty.
  - `failureRecordingFailureRethrowsOriginal()` (**Review Focus 3**): `recordFailure`
    throws. The caught exception is the storage stub's exact instance (`isSameAs`) and has
    one suppressed exception.
  - `deleteFailureRecorded()`: storage `delete` throws on the final release. One failure
    has operation `"delete"`.
  - `deleteFailureRecordingFailureRethrowsOriginal()`: storage `delete` throws, and
    `recordFailure` also throws. The caller receives the storage exception's exact instance
    (`isSameAs`), with one suppressed exception.
- [ ] **Step 2: Run them and confirm they fail.**
  `./mvnw -q -pl dedup4j-spring-boot-starter -am test -Dtest=UploadHistoryRecordingTest
  -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement `StoreOutcome`, `storeWithOutcome`, the wrapper algorithm above,
  the `Dedup4jMetrics` signature change and the wiring.**
- [ ] **Step 4: Run the whole module.** `./mvnw -q -pl dedup4j-spring-boot-starter -am
  test`. Expected: all PASS.
- [ ] **Step 5: Commit.** `feat(history): record completed uploads and failures transactionally`

### Task 6: Management API

**Files:**
- Modify: `dedup4j-spring-boot-management/src/main/java/com/edem/dedup4j/management/Dedup4jManagementSnapshot.java`
- Modify: `…/management/Dedup4jManagementController.java`
- Modify: `…/management/Dedup4jManagementAutoConfiguration.java`
- Create: `…/management/UploadHistoryFailureSource.java`
- Test: `dedup4j-spring-boot-management/src/test/java/com/edem/dedup4j/management/Dedup4jManagementHistoryTest.java`

**Interfaces:**
- Produces:
  - `Info(String instanceId, String instanceName, String provider, String datasetId)`
  - `Metrics(String datasetId, String totalsScope, long uploads, long duplicates,
    long physicalWrites, long skippedPhysicalWrites, long acceptedBytes,
    long avoidedBytes, long contentCount, long physicalBytes)`
  - `Failure(UUID id, Instant occurredAt, String operation, String message,
    String instanceId, String contentHash, String objectKey, long sequenceNo)`
  - `FailureSource` gains `default List<Failure> failuresAfter(long afterSequence,
    int limit)`, which defaults to an empty list (no paging support).
  - `UploadHistoryFailureSource implements FailureSource` (adapter over `UploadHistory`).
    Registered `@ConditionalOnMissingBean(FailureSource.class)`.
  - `GET …/failures`:
    - `?afterSequence=<n>&limit=`: ascending by sequence for ingestion. `afterSequence=0`
      is the first page.
    - `?since=`: descending, for display (unchanged behavior).
    - `limit` defaults to 100 and is capped at 500, in both modes.

- [ ] **Step 1: Write the failing tests** with `@WebMvcTest`-style MockMvc and a stub
  `UploadHistory`.
  - `metricsReadFromHistory()`: the stub totals `("all-time", "ds-1", 5, 2, 3, 500, 200)`
    give JSON `uploads=5`, `duplicates=2`, `physicalWrites=3`,
    `skippedPhysicalWrites=2`, `datasetId="ds-1"`, `totalsScope="all-time"`.
  - `infoIncludesDatasetId()`.
  - `failuresPageWithCursorAndCappedLimit()`: `limit=9999` reaches the stub as `500`, and
    `afterSequence=7` is forwarded.
  - `initialBacklogLargerThanOnePage()`: 250 stub failures. Pages of 100 from
    `afterSequence=0` return sequences `1–100`, `101–200` and `201–250` in ascending
    order, then an empty page.
  - `failuresIncludeOrigin()`: the JSON has `instanceId`, `contentHash` and `objectKey`.
- [ ] **Step 2: Run and confirm FAIL.** `./mvnw -q -pl dedup4j-spring-boot-management -am
  test -Dtest=Dedup4jManagementHistoryTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement the record changes, the controller reading
  `UploadHistory.totals()` (content count and physical bytes unchanged), the adapter and
  the wiring.**
- [ ] **Step 4: Run the whole module.** `./mvnw -q -pl dedup4j-spring-boot-management -am
  test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(management): serve all-time totals and paged failures`

### Task 7: Embedded dashboard

**Files:**
- Modify: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardSnapshotService.java`
- Modify: `…/dashboard/api/EmbeddedDashboardView.java`
- Modify: `…/dashboard/api/EmbeddedDashboardController.java`
- Modify: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/js/dashboard.js` and its HTML
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardHistoryTest.java`

**Interfaces:**
- Consumes: `UploadHistory.totals()`.
- Produces:
  - The `Snapshot` record gains `physicalWrites` and `totalsScope`.
  - `physicalUploads` becomes `physicalWrites`, fixing the bug where it was fed
    `skipped.physical.writes`.
  - `Overview` gains `String totalsScope`.

- [ ] **Step 1: Write the failing tests.**
  - `overviewUsesHistoryTotals()`: the stub totals give overview `uploads=5`,
    `newUploads=3`, `physicalWrites=3`, `totalsScope="all-time"`.
  - `sinceStartScopeExposed()`: a disabled history gives `totalsScope="since-start"`.
- [ ] **Step 2: Run and confirm FAIL.** `./mvnw -q -pl dedup4j-spring-boot-dashboard -am
  test -Dtest=EmbeddedDashboardHistoryTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** In `dashboard.js`:
  - When `totalsScope === "since-start"`, show the caption "since application start" under
    the upload figures. Otherwise show "all time".
  - Rename the "Bytes avoided" label to "Storage avoided", with the spec's definition as a
    `title` tooltip.
  - Read `physicalWrites` instead of `physicalUploads`.
- [ ] **Step 3b: Check the script.** Run `node --check
  dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/js/dashboard.js`.
  Expected: no output. Then `grep -c "Storage avoided"` on the HTML and JS should report at
  least 1.
- [ ] **Step 4: Run the module and confirm PASS.** `./mvnw -q -pl
  dedup4j-spring-boot-dashboard -am test`
- [ ] **Step 5: Commit.** `feat(dashboard): show all-time totals in embedded dashboard`

### Task 8: Standalone dashboard: datasets, baseline deltas, failure ingestion

**Files:**
- Modify: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/persistence/DashboardDatabase.java`
- Create: `…/dashboard/persistence/DatasetTotalsRepository.java`
- Modify: `…/dashboard/persistence/FailureEventRepository.java`
- Modify: `…/dashboard/persistence/MetricSnapshotRepository.java`
- Modify: `…/dashboard/persistence/InstanceRepository.java`
- Modify: `…/dashboard/polling/InstancePollingService.java`
- Modify: `…/dashboard/polling/MetricDeltaCalculator.java`
- Modify: `…/dashboard/api/DashboardController.java`
- Modify: `…/dashboard/api/DashboardView.java`
- Test: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/polling/DatasetPollingTest.java`
  (local `HttpServer` stubs standing in for instances)
- Modify tests: `MetricDeltaCalculatorTest`, `DashboardControllerTest`, `FailureEventRepositoryTest`

**Interfaces:**
- **SQLite migrations** in `DashboardDatabase`. Add each column only when `PRAGMA
  table_info` lacks it, and never touch the preserved fleet `.sqlite` files.
  - `dashboard_instance.dataset_id TEXT`
  - `metric_snapshot.dataset_id TEXT`
  - `failure_event` gains `dataset_id TEXT`, `origin TEXT NOT NULL DEFAULT 'poll'`,
    `source_failure_id TEXT`, `origin_instance_id TEXT`
  - unique index `(dataset_id, source_failure_id)` where
    `source_failure_id IS NOT NULL`
  - new table `failure_cursor(dataset_id TEXT PK, last_sequence_no INTEGER NOT NULL)`
  - **Legacy rows:** after adding the columns, run
    `UPDATE metric_snapshot SET dataset_id = 'instance:' || instance_id WHERE dataset_id IS NULL`,
    and the same for `failure_event`. These are dashboard display tables, not audit tables.
  - new table `dataset_totals(dataset_id TEXT PK, observed_at, uploads, duplicates,
    physical_writes, logical_bytes, avoided_bytes, content_count, physical_bytes,
    totals_scope)`. This is a display cache, upserted on every poll.
- **`MetricDeltaCalculator.calculate(current, previous)`:**
  - `previous == null` returns an all-zero `Delta` (baseline).
  - Deltas are keyed by dataset id in `InstancePollingService.previous`.
- **Dataset key:** `metrics.datasetId`, or `"instance:" + instanceId` when absent (older
  instance).
- **Failure ingestion in `poll`:** after a successful `/metrics` poll, for a dataset not
  yet ingested this cycle:
  - Page `GET /failures?afterSequence=<failure_cursor or 0>&limit=100`, up to 10 pages per
    cycle.
  - After each page, upsert `failure_cursor` to that page's highest `sequenceNo`.
  - Store with `INSERT OR IGNORE`, origin `application`. Skip rows older than
    `now − failureRetention`, but still advance the cursor past them.
  - An older instance (its response has no `sequenceNo`): one
    `since = now − failureRetention` call, deduplicated by the unique index.
- **`DashboardController.overview()`:** totals are the sum over `dataset_totals` rows,
  each dataset once. Trends are the sum of dataset-keyed deltas.
- **Instance status rows:** the `DashboardView` instance row gains `String datasetId` and
  `boolean sharedDataset` (true when another registered instance has the same dataset id),
  and shows its dataset's `dataset_totals` figures.
- **`/instances/{id}/history`:** returns rows whose `dataset_id` is the instance's current
  dataset, or `'instance:' + id` (legacy).
- **Frontend:** in `dedup4j-dashboard/src/main/resources/static/js/dashboard.js` and its
  `index.html`:
  - Show a "shared dataset" badge when `sharedDataset` is true.
  - Rename "Bytes avoided" to "Storage avoided", with the definition as a tooltip.

- [ ] **Step 1: Write the failing tests.**
  - `twoReplicasOneDatasetCountedOnce()`: two stub instances report `datasetId="ds-1"` and
    `uploads=10`. After one cycle, overview `uploads == 10`, and content count and
    physical bytes are counted once.
  - `dashboardRestartRecordsBaseline()`: create a new `InstancePollingService`, poll a
    dataset reporting `uploads=10`. The stored delta has `uploads == 0`, and overview
    `uploads == 10`.
  - `failuresIngestedOnceAcrossReplicasAndRestart()`: both replicas return the same two
    failures. After 2 cycles and a new service instance, 2 rows with origin
    `application` remain.
  - `olderInstanceWithoutDatasetIdIsOwnDataset()` (**Review Focus 5**): the stub returns
    the old `/metrics` shape. Polling succeeds, and `dataset_totals` has the key
    `"instance:<id>"`.
  - `pollFailuresKeepOriginPoll()`: an unreachable instance stores origin `poll`, and
    retention deletes both origins after 7 days.
  - `backlogIngestedAcrossCyclesInOrder()`: the stub has 1500 failures. Cycle 1 ingests
    1000 (10 pages), cycle 2 ingests the remaining 500, and `failure_cursor` is `1500`.
  - `legacyDatabaseUpgradeKeepsHistory()`: create the **old** schema (the
    `DashboardDatabase` DDL as of `HEAD`, copied into the test), insert 3 snapshots and 1
    failure for instance `i-1`, then initialize the new `DashboardDatabase` on that file.
    - All 3 snapshots have `dataset_id = 'instance:i-1'`.
    - `/instances/i-1/history` returns all 3.
    - After `i-1` reports `datasetId="ds-1"` and one more poll, history returns 3 legacy
      rows plus 1 new row.
  - `sharedDatasetFlagged()`: two replicas on `ds-1` both have `sharedDataset == true` in
    `/instances/status`.
- [ ] **Step 2: Run and confirm FAIL.** `./mvnw -q -pl dedup4j-dashboard -am test
  -Dtest=DatasetPollingTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement the migrations, repositories, calculator baseline, polling,
  ingestion and controller changes** above.
- [ ] **Step 4: Run the module and confirm PASS.** `./mvnw -q -pl dedup4j-dashboard -am
  test`, then `node --check dedup4j-dashboard/src/main/resources/static/js/dashboard.js`.
  Expected: no output.
- [ ] **Step 5: Commit.** `feat(dashboard): count datasets once and ingest application failures`

### Task 9: Documentation and full verification

**Files:**
- Modify: `docs/changelog.md`, `docs/decisions.md`, `docs/architecture.md`, `docs/implementation.md`, `README.md`

- [ ] **Step 1: Update the docs.**
  - **README:**
    - history is on by default
    - PostgreSQL and H2 only
    - `dedup4j.history.enabled=false` for other databases
    - the history changelog path for `initialize-schema: never` deployments
    - the term definitions from the spec, including "Storage avoided" and the
      older-instance best-effort failure ingestion
    - the commit-time serialization cost
  - **ADR in `decisions.md`:** durable append-only history replaces in-memory totals, and
    is restricted to PostgreSQL and H2.
  - **Changelog entry**, dated 2026-10-19.
  - **Index files:** update `architecture.md` and `implementation.md` for the new
    packages, tables and endpoint parameters.
- [ ] **Step 2: Run the full reactor.** With OrbStack running, run `./mvnw -q clean
  verify`. Expected: BUILD SUCCESS. The pre-change suite has 190 tests plus the new ones.
- [ ] **Step 3: Commit.** `docs(history): document persistent upload history`
