package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link TransactionalBlobReferences} joined to an application transaction that also writes its own
 * {@code app_reference} row. Subclasses supply an empty database (H2 here, PostgreSQL in a
 * Testcontainers subclass).
 */
abstract class AbstractTransactionalBlobReferencesTest {

    private static final int MAX_UPLOAD_SIZE = 64;

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaTransactionManager transactionManager;
    private AssetContentRepository contents;
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
        inTransaction(() -> entityManager.createNativeQuery(
                        "create table app_reference (id varchar(36) primary key, content_id varchar(36) not null)")
                .executeUpdate());
        contents = new AssetContentRepository(entityManager);
        storage = new MemoryStorage();
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
    void storeCommitsTheCountWithTheApplicationReference() {
        TransactionalBlobReferences references = references();

        BlobReference stored = inTransaction(() -> {
            BlobReference reference = references.store(command("first"));
            insertReference(reference.assetContentId());
            assertThat(registry.find("dedup4j.uploads").counter()).isNull();
            return reference;
        });

        assertThat(refCount(stored.assetContentId())).isEqualTo(1);
        assertThat(applicationReferences(stored.assetContentId())).isEqualTo(1);
        assertThat(storage.objects).containsKey(stored.objectKey());
        assertThat(registry.counter("dedup4j.uploads").count()).isEqualTo(1.0);
    }

    @Test
    void historyIsAppendedLastAndInsideTheTransaction() {
        history.persistent = true;
        TransactionalBlobReferences references = references();

        inTransaction(() -> {
            BlobReference reference = references.store(command("first"));
            insertReference(reference.assetContentId());
            history.events.add("application write");
        });

        assertThat(history.events).containsExactly("application write", "upload in transaction");
        assertThat(history.uploads).singleElement()
                .satisfies(upload -> assertThat(upload.physicalWriteDelta()).isEqualTo(1));
    }

    @Test
    void rollbackUndoesTheCountAndRecordsNoUpload() {
        history.persistent = true;
        TransactionalBlobReferences references = references();
        UUID contentId = inTransaction(() -> references.store(command("shared")).assetContentId());

        inTransaction(status -> {
            insertReference(references.store(command("shared")).assetContentId());
            status.setRollbackOnly();
        });

        assertThat(refCount(contentId)).isEqualTo(1);
        assertThat(applicationReferences(contentId)).isZero();
        assertThat(history.uploads).hasSize(1);
        assertThat(registry.counter("dedup4j.uploads").count()).isEqualTo(1.0);
    }

    @Test
    void rejectsCallsOutsideAReadWriteTransaction() {
        TransactionalBlobReferences references = references();
        UUID contentId = inTransaction(() -> references.store(command("first")).assetContentId());

        assertThatThrownBy(() -> references.store(command("second")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> references.retain(contentId)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> references.release(contentId)).isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> assertThatThrownBy(() -> references.retain(contentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-write"));

        assertThat(refCount(contentId)).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
    }

    @Test
    void rejectedUploadLeavesTheTransactionUsableAndIsRecordedAfterCompletion() {
        TransactionalBlobReferences references = references();

        UUID contentId = inTransaction(status -> {
            assertThatThrownBy(() -> references.store(command("x".repeat(MAX_UPLOAD_SIZE + 1))))
                    .isInstanceOf(BlobValidationException.class);
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(history.failures).isEmpty();
            BlobReference reference = references.store(command("valid"));
            insertReference(reference.assetContentId());
            return reference.assetContentId();
        });

        assertThat(refCount(contentId)).isEqualTo(1);
        assertThat(history.failures).singleElement().isInstanceOf(BlobValidationException.class);
    }

    @Test
    void releaseToZeroDeletesTheObjectOnlyAfterCommit() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("released")));

        inTransaction(() -> {
            references.release(stored.assetContentId());
            assertThat(storage.objects).containsKey(stored.objectKey());
        });

        assertThat(refCount(stored.assetContentId())).isZero();
        assertThat(storage.objects).doesNotContainKey(stored.objectKey());
    }

    @Test
    void rolledBackReleaseKeepsTheCountAndTheObject() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("kept")));

        inTransaction(status -> {
            references.release(stored.assetContentId());
            status.setRollbackOnly();
        });

        assertThat(refCount(stored.assetContentId())).isEqualTo(1);
        assertThat(storage.objects).containsKey(stored.objectKey());
    }

    @Test
    void releaseThenStoreInOneTransactionKeepsTheObject() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("again")));

        inTransaction(() -> {
            references.release(stored.assetContentId());
            references.store(command("again"));
        });

        assertThat(refCount(stored.assetContentId())).isEqualTo(1);
        assertThat(storage.objects).containsKey(stored.objectKey());
    }

    @Test
    void retainRejectsReleasedContentAndMarksTheTransactionForRollback() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("gone")));
        inTransaction(() -> references.release(stored.assetContentId()));

        assertThatThrownBy(() -> inTransaction(status -> {
            assertThatThrownBy(() -> references.retain(stored.assetContentId()))
                    .isInstanceOf(ContentNotFoundException.class);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(refCount(stored.assetContentId())).isZero();
    }

    @Test
    void losingAnInsertRaceAsksForTheWholeTransactionToBeRetried() throws Exception {
        TransactionalBlobReferences references = references();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<UUID> winner = executor.submit(() -> inTransaction(() -> {
            UUID contentId = references.store(command("raced")).assetContentId();
            insertReference(contentId);
            stored.countDown();
            await(finish);
            return contentId;
        }));
        assertThat(stored.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Throwable> loser = executor.submit(() -> catchThrowable(() -> inTransaction(() -> {
            insertReference(references.store(command("raced")).assetContentId());
        })));
        awaitLockWaiter();
        assertThat(loser).isNotDone();
        finish.countDown();
        UUID contentId = winner.get(10, TimeUnit.SECONDS);

        assertThat(loser.get(10, TimeUnit.SECONDS)).isInstanceOf(ContentIdentityConflictException.class);
        assertThat(refCount(contentId)).isEqualTo(1);
        BlobReference retried = inTransaction(() -> {
            BlobReference reference = references.store(command("raced"));
            insertReference(reference.assetContentId());
            return reference;
        });
        assertThat(retried.assetContentId()).isEqualTo(contentId);
        assertThat(retried.duplicate()).isTrue();
        assertThat(refCount(contentId)).isEqualTo(2);
        assertThat(applicationReferences(contentId)).isEqualTo(2);
    }

    @Test
    void theCallersOwnConstraintFailureIsNotAnInsertRace() {
        TransactionalBlobReferences references = references();
        BlobReference existing = inTransaction(() -> references.store(command("existing")));

        Throwable thrown = catchThrowable(() -> inTransaction(() -> {
            // A pending duplicate of committed content, flushed by dedup4j's own statements.
            entityManager.persist(new AssetContent(existing.contentHash().algorithm(), existing.contentHash().hash(),
                    existing.contentHash().sizeBytes(), "other", "test", "bucket", "text/plain", "txt"));
            references.store(command("new content"));
        }));

        assertThat(thrown).isNotNull().isNotInstanceOf(ContentIdentityConflictException.class);
        assertThat(refCount(existing.assetContentId())).isEqualTo(1);
    }

    @Test
    void persistentHistoryRefusesAnotherExplicitIsolationLevel() {
        history.persistent = true;
        TransactionalBlobReferences references = references();
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        repeatableRead.executeWithoutResult(status -> assertThatThrownBy(() -> references.store(command("first")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ COMMITTED"));

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void failedHistoryAppendRollsBackTheUploadAndIsRecorded() {
        history.persistent = true;
        history.failUploads = true;
        TransactionalBlobReferences references = references();

        Throwable thrown = catchThrowable(() -> inTransaction(() -> {
            insertReference(references.store(command("first")).assetContentId());
        }));

        assertThat(thrown).isSameAs(history.uploadFailure);
        assertThat(inTransaction(() -> contents.findPageAfter(null, 10))).isEmpty();
        assertThat(history.failures).containsExactly(history.uploadFailure);
        assertThat(registry.find("dedup4j.uploads").counter()).isNull();
    }

    @Test
    void failedDeleteAfterCommitIsRecordedNotThrown() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("stuck")));
        storage.failDelete = true;

        inTransaction(() -> references.release(stored.assetContentId()));

        assertThat(refCount(stored.assetContentId())).isZero();
        assertThat(storage.objects).containsKey(stored.objectKey());
        assertThat(history.failures).singleElement().isSameAs(storage.deleteFailure);
    }

    @Test
    void anEarlierFailingAfterCommitCallbackDoesNotSkipTheDeleteOrTheMeters() {
        TransactionalBlobReferences references = references();
        BlobReference stored = inTransaction(() -> references.store(command("released")));
        RuntimeException callbackFailure = new IllegalStateException("application callback failed");

        Throwable thrown = catchThrowable(() -> inTransaction(() -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw callbackFailure;
                }
            });
            references.release(stored.assetContentId());
            references.store(command("stored too"));
        }));

        assertThat(thrown).isSameAs(callbackFailure);
        assertThat(storage.objects).doesNotContainKey(stored.objectKey());
        assertThat(registry.counter("dedup4j.uploads").count()).isEqualTo(2.0);
    }

    @Test
    void uploadMadeDuringTheCommitIsStillAppendedToHistory() {
        history.persistent = true;
        TransactionalBlobReferences references = references();

        UUID contentId = inTransaction(() -> {
            java.util.concurrent.atomic.AtomicReference<UUID> stored = new java.util.concurrent.atomic.AtomicReference<>();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    stored.set(references.store(command("during commit")).assetContentId());
                }
            });
            return stored;
        }).get();

        assertThat(refCount(contentId)).isEqualTo(1);
        assertThat(history.uploads).singleElement()
                .satisfies(upload -> assertThat(upload.assetContentId()).isEqualTo(contentId));
        assertThat(registry.counter("dedup4j.uploads").count()).isEqualTo(1.0);
    }

    private TransactionalBlobReferences references() {
        ReferenceCountService referenceCounts = new ReferenceCountService(contents, storage);
        Dedup4jMetrics metrics = new Dedup4jMetrics(registry);
        DefaultBlobDeduplicationService delegate = new DefaultBlobDeduplicationService(
                contents, referenceCounts, new AssetContentMutationService(entityManager, contents), storage,
                new Sha256ContentHasher(), new HashObjectKeyStrategy("joined"), metrics, MAX_UPLOAD_SIZE);
        return new TransactionalBlobReferences(
                delegate, contents, referenceCounts, entityManager, transactionManager, history, metrics);
    }

    private static StoreBlobCommand command(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new StoreBlobCommand(new ByteArrayInputStream(bytes), "file.txt", "text/plain", bytes.length, Map.of());
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
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

    private void inTransaction(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(action);
    }

    private <T> T inTransaction(org.springframework.transaction.support.TransactionCallback<T> action) {
        return new TransactionTemplate(transactionManager).execute(action);
    }

    private void insertReference(UUID contentId) {
        entityManager.createNativeQuery("insert into app_reference (id, content_id) values (?1, ?2)")
                .setParameter(1, UUID.randomUUID().toString())
                .setParameter(2, contentId.toString())
                .executeUpdate();
    }

    private long applicationReferences(UUID contentId) {
        return inTransaction(() -> ((Number) entityManager
                .createNativeQuery("select count(*) from app_reference where content_id = ?1")
                .setParameter(1, contentId.toString())
                .getSingleResult()).longValue());
    }

    private long refCount(UUID contentId) {
        return inTransaction(() -> contents.findByIdForUpdate(contentId).orElseThrow().getRefCount());
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
        throw new AssertionError("the second upload never waited on the first one's insert");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MemoryStorage implements BlobStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final RuntimeException deleteFailure = new com.edem.dedup4j.core.exception.BlobStorageException(
                "delete failed");
        private volatile boolean failDelete;

        @Override
        public StoredBlob put(PutBlobRequest request) {
            try {
                objects.put(request.objectKey(), request.content().readAllBytes());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return new StoredBlob(request.objectKey(), "memory", "bucket", request.sizeBytes(),
                    request.contentType(), null, Instant.now());
        }

        @Override
        public BlobResource get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            if (failDelete) {
                throw deleteFailure;
            }
            objects.remove(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            return objects.containsKey(objectKey);
        }
    }

    private static final class RecordingHistory implements UploadHistory {
        private final List<UploadContribution> uploads = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final RuntimeException uploadFailure = new IllegalStateException("ledger unavailable");
        private volatile boolean persistent;
        private volatile boolean failUploads;

        @Override
        public void recordUpload(UploadContribution contribution) {
            if (failUploads) {
                throw uploadFailure;
            }
            events.add(TransactionSynchronizationManager.isActualTransactionActive()
                    ? "upload in transaction" : "upload outside transaction");
            uploads.add(contribution);
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
            return persistent;
        }
    }
}
