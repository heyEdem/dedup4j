package com.edem.dedup4j.cleanup;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.core.storage.ListedBlob;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import com.edem.dedup4j.service.DefaultBlobDeduplicationService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OrphanedObjectSweep} against a real database. Subclasses supply an empty database (H2 here,
 * PostgreSQL in a Testcontainers subclass).
 */
abstract class AbstractOrphanedObjectSweepTest {

    private static final String PREFIX = "sweep";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(2));

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaTransactionManager transactionManager;
    private AssetContentRepository contents;
    private AssetContentMutationService mutations;
    private ReferenceCountService referenceCounts;
    private MemoryStorage storage;
    private RecordingHistory history;
    private SimpleMeterRegistry registry;
    private ExecutorService executor;

    /** JDBC settings for a new, empty database. */
    protected abstract Map<String, Object> connectionProperties();

    /** Counts sessions waiting on another session's lock, including a unique-key insert waiting on an uncommitted one. */
    protected abstract String lockWaitersQuery();

    @BeforeEach
    void createDatabase() {
        Map<String, Object> properties = new HashMap<>(connectionProperties());
        properties.put("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test", properties);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setJpaDialect(new HibernateJpaDialect());
        contents = new AssetContentRepository(entityManager);
        mutations = new AssetContentMutationService(entityManager, contents);
        storage = new MemoryStorage();
        referenceCounts = new ReferenceCountService(contents, storage);
        history = new RecordingHistory();
        registry = new SimpleMeterRegistry();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void close() {
        executor.shutdownNow();
        entityManagerFactory.close();
    }

    @Test
    void objectLeftByARolledBackUploadIsAdoptedAndDeleted() {
        DefaultBlobDeduplicationService uploads = uploads();
        String[] key = new String[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            key[0] = uploads.store(command("rolled back")).objectKey();
            status.setRollbackOnly();
        });
        storage.age(key[0]);
        assertThat(storage.objects).containsKey(key[0]);

        OrphanSweepResult result = sweep().run();

        assertThat(result).isEqualTo(new OrphanSweepResult(1, 0, 0, 0, 1, 0, 0));
        assertThat(storage.objects).doesNotContainKey(key[0]);
        assertThat(registry.counter("dedup4j.cleanup.orphans.deleted").count()).isEqualTo(1.0);
        AssetContent adopted = inTransaction(() -> contents.findAll()).getFirst();
        assertThat(adopted.getObjectKey()).isEqualTo(key[0]);
        assertThat(adopted.getRefCount()).isZero();
        assertThat(adopted.getHashAlgorithm()).isEqualTo(hash("rolled back").algorithm());
        assertThat(adopted.getContentType()).isEqualTo("text/plain");
    }

    @Test
    void uploadOfAdoptedContentRestoresItsObject() {
        String key = orphan("comes back");
        sweep().run();
        assertThat(storage.objects).doesNotContainKey(key);

        BlobReference restored = inTransaction(() -> uploads().store(command("comes back")));

        assertThat(restored.objectKey()).isEqualTo(key);
        assertThat(storage.objects).containsKey(key);
        assertThat(refCount(restored)).isEqualTo(1);
        assertThat(inTransaction(() -> contents.findAll())).hasSize(1);
    }

    @Test
    void objectsWithinTheGracePeriodAreKept() {
        String key = orphan("recent");
        storage.lastModified.put(key, NOW.minus(Duration.ofHours(1)));

        OrphanSweepResult result = sweep().run();

        assertThat(result.recent()).isEqualTo(1);
        assertThat(storage.objects).containsKey(key);
        assertThat(inTransaction(() -> contents.findAll())).isEmpty();
    }

    @Test
    void objectsAContentRowPointsToAreKept() {
        BlobReference stored = inTransaction(() -> uploads().store(command("referenced")));
        storage.age(stored.objectKey());

        OrphanSweepResult result = sweep().run();

        assertThat(result.owned()).isEqualTo(1);
        assertThat(storage.objects).containsKey(stored.objectKey());
        assertThat(refCount(stored)).isEqualTo(1);
    }

    @Test
    void objectsWhoseBytesDoNotMatchTheirKeyAreNeverDeleted() {
        String key = new HashObjectKeyStrategy(PREFIX).generateKey(hash("expected"));
        storage.putAged(key, "something else");
        storage.putAged(PREFIX + "/notes.txt", "not ours");

        OrphanSweepResult result = sweep().run();

        assertThat(result.unrecognized()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
        assertThat(storage.objects).containsKeys(key, PREFIX + "/notes.txt");
        assertThat(inTransaction(() -> contents.findAll())).isEmpty();
    }

    @Test
    void uploadCommittingDuringThePassKeepsItsObject() throws Exception {
        String key = orphan("raced");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // An upload that wrote the object and inserted its row, but has not committed.
        Future<?> upload = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ContentHash hash = hash("raced");
            mutations.createOrRetain(new AssetContent(hash.algorithm(), hash.hash(), hash.sizeBytes(), key,
                    "memory", "bucket", "text/plain", "txt"));
            inserted.countDown();
            await(commit);
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<OrphanSweepResult> pass = executor.submit(() -> sweep().run());
        awaitLockWaiter();
        assertThat(pass).isNotDone();
        commit.countDown();
        upload.get(10, TimeUnit.SECONDS);

        OrphanSweepResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.owned()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(storage.objects).containsKey(key);
        AssetContent row = inTransaction(() -> contents.findAll()).getFirst();
        assertThat(row.getRefCount()).isEqualTo(1);
    }

    @Test
    void uploadRolledBackDuringThePassLetsTheSweepDeleteTheObject() throws Exception {
        String key = orphan("abandoned");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<?> upload = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ContentHash hash = hash("abandoned");
            mutations.createOrRetain(new AssetContent(hash.algorithm(), hash.hash(), hash.sizeBytes(), key,
                    "memory", "bucket", "text/plain", "txt"));
            inserted.countDown();
            await(finish);
            status.setRollbackOnly();
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<OrphanSweepResult> pass = executor.submit(() -> sweep().run());
        awaitLockWaiter();
        finish.countDown();
        upload.get(10, TimeUnit.SECONDS);

        assertThat(pass.get(10, TimeUnit.SECONDS).deleted()).isEqualTo(1);
        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void failedObjectIsRecordedAndThePassCarriesOn() {
        String failing = orphan("unreadable");
        String other = orphan("readable");
        storage.unreadable.add(failing);

        OrphanSweepResult result = sweep().run();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(storage.objects).containsKey(failing).doesNotContainKey(other);
        assertThat(history.failures).singleElement().isInstanceOf(BlobStorageException.class);
    }

    @Test
    void objectGoneBeforeItIsReadIsCountedAsVanished() {
        String key = orphan("gone");
        storage.vanishOnRead.add(key);

        assertThat(sweep().run().vanished()).isEqualTo(1);
    }

    @Test
    void uploadWhoseObjectIsAdoptedAndDeletedMidwayWritesItBack() throws Exception {
        String key = new HashObjectKeyStrategy(PREFIX).generateKey(hash("overtaken"));
        OrphanSweepResult[] result = new OrphanSweepResult[1];
        // Between the upload's lookup and its insert: the sweep adopts the object it just wrote and deletes it.
        storage.afterPut = putKey -> {
            storage.age(putKey);
            result[0] = executor.submit(() -> sweep().run()).get(10, TimeUnit.SECONDS);
        };

        BlobReference stored = inTransaction(() -> uploads().store(command("overtaken")));

        assertThat(result[0].deleted()).isEqualTo(1);
        assertThat(stored.objectKey()).isEqualTo(key);
        assertThat(storage.objects).containsKey(key);
        assertThat(refCount(stored)).isEqualTo(1);
    }

    @Test
    void interruptThatFailsAnObjectEndsThePassAsCancelled() {
        orphan("aborted read");
        storage.interruptOnRead = true;
        try {
            assertThatThrownBy(() -> sweep().run())
                    .isInstanceOf(java.util.concurrent.CancellationException.class)
                    .hasCauseInstanceOf(BlobStorageException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(history.failures).isEmpty();
    }

    @Test
    void failedSweepStillRunsTheReleasedObjectPass() {
        String released = orphan("released earlier");
        ContentHash hash = hash("released earlier");
        inTransaction(() -> mutations.insertReleased(new AssetContent(hash.algorithm(), hash.hash(), hash.sizeBytes(),
                released, "memory", "bucket", "text/plain", "txt")));
        storage.failList = true;
        ScheduledCleanup cleanup = new ScheduledCleanup(
                new ReleasedObjectCleanup(contents, referenceCounts, transactionManager, history,
                        new Dedup4jMetrics(registry), 10),
                sweep(), new org.springframework.scheduling.support.CronTrigger("0 0 3 * * *"));

        assertThatThrownBy(cleanup::runNow).isInstanceOf(BlobStorageException.class).hasMessage("list failed");
        assertThat(storage.objects).doesNotContainKey(released);
    }

    @Test
    void refusesAnEmptyPrefixAndAnAmbientTransaction() {
        assertThatThrownBy(() -> sweep("").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dedup4j.storage.key-prefix");
        assertThatThrownBy(() -> sweep(PREFIX + "/").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trailing slash");
        assertThatThrownBy(() -> inTransaction(() -> sweep().run()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    @Test
    void interruptedPassStopsBeforeTheNextObject() {
        String key = orphan("interrupted");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sweep().run()).isInstanceOf(java.util.concurrent.CancellationException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(storage.objects).containsKey(key);
    }

    private OrphanedObjectSweep sweep() {
        return sweep(PREFIX);
    }

    private OrphanedObjectSweep sweep(String prefix) {
        return new OrphanedObjectSweep(storage, new Sha256ContentHasher(), new HashObjectKeyStrategy(PREFIX),
                contents, mutations, referenceCounts, transactionManager, history, new Dedup4jMetrics(registry),
                prefix, Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DefaultBlobDeduplicationService uploads() {
        return new DefaultBlobDeduplicationService(contents, referenceCounts, mutations, storage,
                new Sha256ContentHasher(), new HashObjectKeyStrategy(PREFIX), new Dedup4jMetrics(registry), 1024);
    }

    /** Writes an object at the key its bytes hash to, with no row, as an abandoned upload leaves it. */
    private String orphan(String text) {
        String key = new HashObjectKeyStrategy(PREFIX).generateKey(hash(text));
        storage.putAged(key, text);
        return key;
    }

    private static ContentHash hash(String text) {
        try {
            return new Sha256ContentHasher().hash(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static StoreBlobCommand command(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new StoreBlobCommand(new ByteArrayInputStream(bytes), "file.txt", "text/plain", bytes.length, Map.of());
    }

    private long refCount(BlobReference reference) {
        return inTransaction(() -> contents.findByIdForUpdate(reference.assetContentId()).orElseThrow().getRefCount());
    }

    private <T> T inTransaction(Callable<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Number waiters = inTransaction(() ->
                    (Number) entityManager.createNativeQuery(lockWaitersQuery()).getSingleResult());
            if (waiters.longValue() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the sweep never waited on the upload's insert");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MemoryStorage implements ListableBlobStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Map<String, Instant> lastModified = new ConcurrentHashMap<>();
        private final Set<String> unreadable = ConcurrentHashMap.newKeySet();
        private final Set<String> vanishOnRead = ConcurrentHashMap.newKeySet();
        private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
        private volatile PutHook afterPut;
        private volatile boolean interruptOnRead;
        private volatile boolean failList;

        void putAged(String key, String text) {
            objects.put(key, text.getBytes(StandardCharsets.UTF_8));
            contentTypes.put(key, "text/plain");
            age(key);
        }

        void age(String key) {
            lastModified.put(key, OLD);
        }

        @Override
        public StoredBlob put(PutBlobRequest request) {
            try {
                objects.put(request.objectKey(), request.content().readAllBytes());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
            lastModified.put(request.objectKey(), NOW);
            contentTypes.put(request.objectKey(), request.contentType());
            PutHook hook = afterPut;
            if (hook != null) {
                afterPut = null;
                try {
                    hook.run(request.objectKey());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return new StoredBlob(request.objectKey(), "memory", "bucket", request.sizeBytes(),
                    request.contentType(), null, NOW);
        }

        @Override
        public BlobResource get(String objectKey) {
            if (unreadable.contains(objectKey)) {
                throw new BlobStorageException("read failed");
            }
            if (interruptOnRead) {
                Thread.currentThread().interrupt();
                throw new BlobStorageException("read aborted");
            }
            byte[] bytes = vanishOnRead.contains(objectKey) ? null : objects.get(objectKey);
            if (bytes == null) {
                throw new ContentNotFoundException("missing: " + objectKey);
            }
            return new BlobResource(objectKey, new ByteArrayInputStream(bytes), bytes.length,
                    contentTypes.get(objectKey), Map.of());
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            return objects.containsKey(objectKey);
        }

        @Override
        public Stream<ListedBlob> list(String keyPrefix) {
            if (failList) {
                throw new BlobStorageException("list failed");
            }
            return objects.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(keyPrefix))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ListedBlob(entry.getKey(), "memory", "bucket", entry.getValue().length,
                            Optional.ofNullable(lastModified.get(entry.getKey())).orElse(NOW)));
        }
    }

    @FunctionalInterface
    private interface PutHook {
        void run(String objectKey) throws Exception;
    }

    private static final class RecordingHistory implements UploadHistory {
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();

        @Override
        public void recordUpload(UploadContribution contribution) {
        }

        @Override
        public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) {
            failures.add(failure);
        }

        @Override
        public HistoryTotals totals() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FailureRecord> recentFailures(Instant since, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean persistent() {
            return false;
        }
    }
}
