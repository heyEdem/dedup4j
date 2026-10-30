package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.jpa.ReferenceRepair;
import com.edem.dedup4j.jpa.ReferenceRepairRepository;
import com.edem.dedup4j.jpa.ReferenceRepairService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CoordinatedRepair} against an application that follows the writer contract: its
 * {@code app_reference} rows and the dedup4j count change in one transaction. Subclasses supply an
 * empty database (H2 here, PostgreSQL in a Testcontainers subclass).
 */
abstract class AbstractCoordinatedRepairTest {

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaTransactionManager transactionManager;
    private AssetContentRepository contents;
    private ReferenceCountService referenceCounts;
    private ReferenceRepairRepository audit;
    private RecordingStorage storage;
    private SimpleMeterRegistry registry;
    private HookingTransactionManager hooking;
    private AtomicInteger lookups;
    private ExecutorService executor;

    /** JDBC settings for a new, empty database; each test gets its own, so no drift carries over. */
    protected abstract Map<String, Object> connectionProperties();

    /** The JDBC product name, as {@code DatabaseMetaData} reports it. */
    protected abstract String databaseProduct();

    /** Counts sessions that are waiting on a row lock another session holds. */
    protected abstract String lockWaitersQuery();

    @BeforeEach
    void createDatabase() {
        Map<String, Object> properties = new java.util.HashMap<>(connectionProperties());
        // Spring Boot's default; Hibernate must hold its connection for the isolation level to be set.
        properties.put("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test", properties);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        // As Spring Boot configures it; the default dialect cannot set an isolation level.
        transactionManager.setJpaDialect(new HibernateJpaDialect());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager
                .createNativeQuery("create table app_reference (id varchar(36) primary key, content_id varchar(36) not null)")
                .executeUpdate());
    }

    @BeforeEach
    void createCollaborators() {
        contents = new AssetContentRepository(entityManager);
        storage = new RecordingStorage();
        referenceCounts = new ReferenceCountService(contents, storage);
        audit = new ReferenceRepairRepository(entityManager);
        registry = new SimpleMeterRegistry();
        hooking = new HookingTransactionManager(transactionManager);
        lookups = new AtomicInteger();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void close() {
        executor.shutdownNow();
        entityManagerFactory.close();
    }

    @Test
    void repairsDriftBothWaysAndRecordsEachRepair() {
        AssetContent tooHigh = content(3, 1);
        AssetContent tooLow = content(1, 2);

        CoordinatedRepairResult result = repair().run(this::bulkCounts, this::lookup);

        assertThat(refCount(tooHigh)).isEqualTo(1);
        assertThat(refCount(tooLow)).isEqualTo(2);
        assertThat(result.repaired()).isEqualTo(2);
        assertThat(repairsOf(tooHigh)).singleElement().satisfies(repair -> {
            assertThat(repair.getPreviousCount()).isEqualTo(3);
            assertThat(repair.getRepairedCount()).isEqualTo(1);
            assertThat(repair.getRunId()).isEqualTo(result.runId());
            assertThat(repair.getInstanceId()).isEqualTo("test-instance");
            assertThat(repair.getCompensatesRepairId()).isNull();
        });
        assertThat(registry.counter("dedup4j.repairs").count()).isEqualTo(2.0);
    }

    @Test
    void waitsForAWriterInFlightThenFindsNothingToRepair() throws Exception {
        AssetContent content = content(1, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> addReference(content, locked, finish, false));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // Discovery is told the count is off, so the row is rechecked while the writer holds its lock.
        Future<CoordinatedRepairResult> repair = executor.submit(() ->
                repair().run(() -> Map.of(content.getId(), 5L), this::lookup));
        awaitLockWaiter();
        assertThat(repair).isNotDone();
        assertThat(lookups).hasValue(0);
        finish.countDown();
        writer.get(10, TimeUnit.SECONDS);
        CoordinatedRepairResult result = repair.get(10, TimeUnit.SECONDS);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.consistent()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
        assertThat(refCount(content)).isEqualTo(2);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void waitsForAWriterThatRollsBackThenFindsNothingToRepair() throws Exception {
        AssetContent content = content(1, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> addReference(content, locked, finish, true));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<CoordinatedRepairResult> repair = executor.submit(() ->
                repair().run(() -> Map.of(content.getId(), 5L), this::lookup));
        awaitLockWaiter();
        assertThat(repair).isNotDone();
        assertThat(lookups).hasValue(0);
        finish.countDown();
        writer.get(10, TimeUnit.SECONDS);
        CoordinatedRepairResult result = repair.get(10, TimeUnit.SECONDS);

        assertThat(result.consistent()).isEqualTo(1);
        assertThat(refCount(content)).isEqualTo(1);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void repairToZeroDeletesTheObjectAfterCommit() {
        AssetContent content = content(2, 0);

        CoordinatedRepairResult result = repair().run(this::bulkCounts, this::lookup);

        assertThat(result.repaired()).isEqualTo(1);
        assertThat(refCount(content)).isZero();
        assertThat(storage.deletedKeys).containsExactly(content.getObjectKey());
    }

    @Test
    void contentRestoredBeforeTheDeleteKeepsItsObject() {
        AssetContent content = content(2, 0);
        // The repair's own transaction is the first write; the post-commit delete is the second.
        hooking.beforeWrite(2, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> referenceCounts.restoreReleased(content.getId())));

        repair().run(this::bulkCounts, this::lookup);

        assertThat(refCount(content)).isEqualTo(1);
        assertThat(storage.deletedKeys).isEmpty();
        assertThat(repairsOf(content)).singleElement()
                .satisfies(repair -> assertThat(repair.getRepairedCount()).isZero());
    }

    @Test
    void releasedContentStillReferencedStaysAtZero() {
        AssetContent content = content(0, 1);

        CoordinatedRepairResult result = repair().run(this::bulkCounts, this::lookup);

        assertThat(result.released()).isEqualTo(1);
        assertThat(refCount(content)).isZero();
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void failedAuditRecordRollsBackTheCountChange() {
        AssetContent content = content(3, 1);
        CoordinatedRepair tooLongInstance = repair("i".repeat(300));

        CoordinatedRepairResult result = tooLongInstance.run(this::bulkCounts, this::lookup);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(refCount(content)).isEqualTo(3);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void failedLookupChangesNothing() {
        AssetContent content = content(3, 1);

        CoordinatedRepairResult result = repair().run(this::bulkCounts, id -> {
            throw new IllegalStateException("reference table unavailable");
        });

        assertThat(result.failed()).isEqualTo(1);
        assertThat(refCount(content)).isEqualTo(3);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void negativeLookupChangesNothing() {
        AssetContent content = content(3, 1);

        CoordinatedRepairResult result = repair().run(this::bulkCounts, id -> -1L);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(refCount(content)).isEqualTo(3);
    }

    @Test
    void rejectsACallInsideATransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                repair().run(this::bulkCounts, this::lookup)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    @Test
    void transactionManagerWithoutIsolationSupportStopsTheRunWithoutChanges() {
        AssetContent content = content(3, 1);
        CoordinatedRepair withoutIsolation = new CoordinatedRepair(contents, new ReferenceRepairService(contents, audit),
                referenceCounts, new JpaTransactionManager(entityManagerFactory), new DisabledUploadHistory(),
                new Dedup4jMetrics(registry), "test-instance", Clock.systemUTC(), 2, this::databaseProduct);

        assertThatThrownBy(() -> withoutIsolation.run(this::bulkCounts, this::lookup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ COMMITTED");
        assertThat(refCount(content)).isEqualTo(3);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void refusesADatabaseWithoutAnAppendOnlyAudit() {
        AssetContent content = content(3, 1);
        CoordinatedRepair onMySql = new CoordinatedRepair(contents, new ReferenceRepairService(contents, audit),
                referenceCounts, hooking, new DisabledUploadHistory(), new Dedup4jMetrics(registry),
                "test-instance", Clock.systemUTC(), 2, () -> "MySQL");

        assertThatThrownBy(() -> onMySql.run(this::bulkCounts, this::lookup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL or H2");
        assertThat(refCount(content)).isEqualTo(3);
        assertThat(repairsOf(content)).isEmpty();
    }

    @Test
    void secondPassFindsNothingLeftToRepair() {
        AssetContent content = content(4, 2);

        repair().run(this::bulkCounts, this::lookup);
        CoordinatedRepairResult second = repair().run(this::bulkCounts, this::lookup);

        assertThat(second.candidates()).isZero();
        assertThat(repairsOf(content)).hasSize(1);
    }

    @Test
    void scheduledRepairRecordsTheRepairResultAndLeavesTheDriftGauges() {
        AssetContent content = content(3, 1);
        ScheduledReconciliation scheduled = scheduledRepair(repair());

        ReconciliationRun run = scheduled.runNow();

        assertThat(run.mode()).isEqualTo(ReconciliationMode.REPAIR);
        assertThat(run.outcome()).isEqualTo(ReconciliationRun.Outcome.SUCCEEDED);
        assertThat(run.summary()).isNull();
        assertThat(run.repair().repaired()).isEqualTo(1);
        assertThat(refCount(content)).isEqualTo(1);
        assertThat(registry.get("dedup4j.reconciliation.runs").tag("outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.find("dedup4j.reconciliation.mismatches").gauge()).isNull();
    }

    @Test
    void scheduledRepairRefusesARunNowInsideATransaction() {
        AssetContent content = content(3, 1);
        ScheduledReconciliation scheduled = scheduledRepair(repair());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThatThrownBy(scheduled::runNow)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("outside a transaction"));

        assertThat(scheduled.lastRun()).isEmpty();
        assertThat(refCount(content)).isEqualTo(3);
    }

    @Test
    void failedScheduledRepairKeepsItsMode() {
        ScheduledReconciliation scheduled = scheduledRepair(new CoordinatedRepair(contents,
                new ReferenceRepairService(contents, audit), referenceCounts, hooking, new DisabledUploadHistory(),
                new Dedup4jMetrics(registry), "test-instance", Clock.systemUTC(), 2, () -> "MySQL"));

        ReconciliationRun run = scheduled.runNow();

        assertThat(run.mode()).isEqualTo(ReconciliationMode.REPAIR);
        assertThat(run.outcome()).isEqualTo(ReconciliationRun.Outcome.FAILED);
        assertThat(run.failureMessage()).contains("PostgreSQL or H2");
    }

    @Test
    void interruptDuringARowEndsThePassAsCancelledNotFailed() {
        AssetContent first = content(3, 1);
        content(3, 1);

        try {
            assertThatThrownBy(() -> repair().run(this::bulkCounts, contentId -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("lookup interrupted");
            })).isInstanceOf(CancellationException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(refCount(first)).isEqualTo(3);
    }

    @Test
    void cancellationFromTheLookupEndsThePassEvenWithoutTheInterruptFlag() {
        AssetContent first = content(3, 1);
        AssetContent second = content(3, 1);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> repair().run(this::bulkCounts, contentId -> {
            calls.incrementAndGet();
            throw new CancellationException("lookup cancelled");
        })).isInstanceOf(CancellationException.class).hasMessage("lookup cancelled");

        assertThat(calls).hasValue(1);
        assertThat(refCount(first)).isEqualTo(3);
        assertThat(refCount(second)).isEqualTo(3);
    }

    @Test
    void interruptDuringTheLastRowStillEndsThePassAsCancelled() {
        AssetContent content = content(3, 1);

        try {
            assertThatThrownBy(() -> repair().run(this::bulkCounts, contentId -> {
                Thread.currentThread().interrupt();
                return lookup(contentId);
            })).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
        // The row's own transaction completed before the pass noticed the interrupt.
        assertThat(refCount(content)).isEqualTo(1);
        assertThat(repairsOf(content)).hasSize(1);
    }

    private ScheduledReconciliation scheduledRepair(CoordinatedRepair repair) {
        return new ScheduledReconciliation(repair, this::bulkCounts, this::lookup,
                new org.springframework.scheduling.support.CronTrigger("0 0 3 * * *"),
                new Dedup4jMetrics(registry), Clock.systemUTC());
    }

    private CoordinatedRepair repair() {
        return repair("test-instance");
    }

    private CoordinatedRepair repair(String instanceId) {
        return new CoordinatedRepair(contents, new ReferenceRepairService(contents, audit), referenceCounts,
                hooking, new DisabledUploadHistory(), new Dedup4jMetrics(registry), instanceId,
                Clock.systemUTC(), 2, this::databaseProduct);
    }

    /** Waits until the database reports a session blocked on a lock: repair, waiting for the writer. */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Number waiters = new TransactionTemplate(transactionManager).execute(status ->
                    (Number) entityManager.createNativeQuery(lockWaitersQuery()).getSingleResult());
            if (waiters.longValue() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("repair never waited on the writer's lock");
    }

    /** The application's writer: one transaction adds its reference row and retains the content. */
    private void addReference(AssetContent content, CountDownLatch locked, CountDownLatch finish, boolean rollBack) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            referenceCounts.retain(content.getId());
            insertReference(content.getId());
            locked.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (rollBack) {
                status.setRollbackOnly();
            }
        });
    }

    /** Stored count {@code refCount} with {@code references} committed application rows. */
    private AssetContent content(long refCount, int references) {
        String hash = UUID.randomUUID().toString().replace("-", "");
        AssetContent content = new AssetContent("sha-256", hash, 3L, "repair/" + hash, "test", "bucket",
                "text/plain", "txt");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(content);
            for (long count = 1; count < refCount; count++) {
                referenceCounts.retain(content.getId());
            }
            if (refCount == 0) {
                referenceCounts.release(content.getId());
            }
            for (int reference = 0; reference < references; reference++) {
                insertReference(content.getId());
            }
        });
        storage.presentKeys.add(content.getObjectKey());
        return content;
    }

    private void insertReference(UUID contentId) {
        entityManager.createNativeQuery("insert into app_reference (id, content_id) values (?1, ?2)")
                .setParameter(1, UUID.randomUUID().toString())
                .setParameter(2, contentId.toString())
                .executeUpdate();
    }

    /** Authoritative count, through the shared EntityManager so it runs in the repair transaction. */
    private long lookup(UUID contentId) {
        lookups.incrementAndGet();
        return ((Number) entityManager.createNativeQuery("select count(*) from app_reference where content_id = ?1")
                .setParameter(1, contentId.toString())
                .getSingleResult()).longValue();
    }

    private Map<UUID, Long> bulkCounts() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Map<UUID, Long> counts = new java.util.HashMap<>();
            for (Object result : entityManager.createNativeQuery(
                            "select content_id, count(*) from app_reference group by content_id")
                    .getResultList()) {
                Object[] row = (Object[]) result;
                counts.put(UUID.fromString((String) row[0]), ((Number) row[1]).longValue());
            }
            return counts;
        });
    }

    private long refCount(AssetContent content) {
        return new TransactionTemplate(transactionManager).execute(status ->
                contents.findByIdForUpdate(content.getId()).orElseThrow().getRefCount());
    }

    private List<ReferenceRepair> repairsOf(AssetContent content) {
        return new TransactionTemplate(transactionManager).execute(status -> audit.findByContent(content.getId()));
    }

    /** Runs a hook once, just before the Nth read-write transaction begins. */
    private static final class HookingTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private int writes;
        private int hookAt;
        private Runnable hook;

        private HookingTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        private void beforeWrite(int write, Runnable hook) {
            this.hookAt = write;
            this.hook = hook;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            if (definition != null && !definition.isReadOnly() && ++writes == hookAt && hook != null) {
                Runnable run = hook;
                hook = null;
                run.run();
            }
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            delegate.commit(status);
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            delegate.rollback(status);
        }
    }

    private static final class RecordingStorage implements BlobStorage {
        private final Set<String> presentKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final List<String> deletedKeys = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
        @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }

        @Override
        public void delete(String objectKey) {
            presentKeys.remove(objectKey);
            deletedKeys.add(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            return presentKeys.contains(objectKey);
        }
    }
}
