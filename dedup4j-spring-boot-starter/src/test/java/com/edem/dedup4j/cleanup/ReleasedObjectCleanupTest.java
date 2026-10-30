package com.edem.dedup4j.cleanup;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Other test classes share this H2 database and may leave released rows behind, so assertions
 * name this test's objects instead of totals. Their objects are absent from this storage.
 */
class ReleasedObjectCleanupTest {

    private static EntityManagerFactory entityManagerFactory;

    private JpaTransactionManager transactionManager;
    private HookingTransactionManager hookingTransactionManager;
    private AssetContentRepository repository;
    private RecordingStorage storage;
    private ReferenceCountService referenceCountService;
    private RecordingHistory history;
    private SimpleMeterRegistry registry;
    private ReleasedObjectCleanup cleanup;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createCleanup() {
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        hookingTransactionManager = new HookingTransactionManager(transactionManager);
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        repository = new AssetContentRepository(entityManager);
        storage = new RecordingStorage();
        referenceCountService = new ReferenceCountService(repository, storage);
        history = new RecordingHistory();
        registry = new SimpleMeterRegistry();
        cleanup = new ReleasedObjectCleanup(
                repository, referenceCountService, hookingTransactionManager, history, new Dedup4jMetrics(registry), 2
        );
    }

    @Test
    void deletesLeftoverObjectOnceAndSkipsItOnTheNextPass() {
        AssetContent content = releasedContentWithObject();

        ReleasedCleanupResult first = cleanup.run();
        ReleasedCleanupResult second = cleanup.run();

        assertThat(storage.deletedKeys).containsExactly(content.getObjectKey());
        assertThat(first.deleted()).isEqualTo(1);
        assertThat(second.deleted()).isZero();
        assertThat(second.checked()).isZero();
        assertThat(registry.counter("dedup4j.cleanup.deleted").count()).isEqualTo(1.0);
    }

    @Test
    void pagesThroughEveryReleasedRow() {
        List<String> keys = List.of(
                releasedContentWithObject().getObjectKey(),
                releasedContentWithObject().getObjectKey(),
                releasedContentWithObject().getObjectKey()
        );

        cleanup.run();

        assertThat(storage.deletedKeys).containsExactlyInAnyOrderElementsOf(keys);
    }

    @Test
    void keepsObjectOfContentRestoredAfterThePassListedIt() {
        AssetContent content = releasedContentWithObject();
        hookingTransactionManager.beforeFirstWrite = () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> referenceCountService.restoreReleased(content.getId()));
        // One page, so the row is listed before the restore; a later page would not list it at all.
        ReleasedObjectCleanup onePage = new ReleasedObjectCleanup(
                repository, referenceCountService, hookingTransactionManager, history, new Dedup4jMetrics(registry), 10_000
        );

        ReleasedCleanupResult result = onePage.run();

        assertThat(storage.deletedKeys).doesNotContain(content.getObjectKey());
        assertThat(result.referenced()).isGreaterThanOrEqualTo(1);
        assertThat(refCount(content)).isEqualTo(1L);
    }

    @Test
    void failedDeleteIsRecordedLeftVisibleAndRetriedOnTheNextPass() {
        AssetContent content = releasedContentWithObject();
        storage.failingKeys.add(content.getObjectKey());

        ReleasedCleanupResult failed = cleanup.run();

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.leftoverCount()).isEqualTo(1);
        assertThat(storage.deletedKeys).isEmpty();
        assertThat(history.failedObjectKeys).containsExactly(content.getObjectKey());
        assertThat(registry.get("dedup4j.cleanup.remaining").gauge().value()).isEqualTo(1.0);
        assertThat(registry.counter("dedup4j.storage.delete.failures").count()).isEqualTo(1.0);

        storage.failingKeys.clear();
        ReleasedCleanupResult retried = cleanup.run();

        assertThat(retried.failed()).isZero();
        assertThat(storage.deletedKeys).containsExactly(content.getObjectKey());
        assertThat(registry.get("dedup4j.cleanup.remaining").gauge().value()).isZero();
    }

    @Test
    void interruptedPassStopsAndKeepsCountOfDeletesAlreadyCommitted() {
        releasedContentWithObject();
        releasedContentWithObject();
        storage.interruptAfterDelete = true;

        try {
            assertThatThrownBy(cleanup::run).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }

        assertThat(storage.deletedKeys).hasSize(1);
        assertThat(registry.counter("dedup4j.cleanup.deleted").count()).isEqualTo(1.0);
    }

    @Test
    void historyRecordingFailureDoesNotStopThePass() {
        AssetContent failing = releasedContentWithObject();
        AssetContent next = releasedContentWithObject();
        storage.failingKeys.add(failing.getObjectKey());
        history.failRecording = true;

        ReleasedCleanupResult result = cleanup.run();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(storage.deletedKeys).containsExactly(next.getObjectKey());
    }

    private AssetContent releasedContentWithObject() {
        String hash = UUID.randomUUID().toString().replace("-", "");
        AssetContent content = new AssetContent(
                "sha-256", hash, 3L, "cleanup/" + hash, "test", "bucket", "text/plain", "txt"
        );
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions.executeWithoutResult(status -> entityManager.persist(content));
        transactions.executeWithoutResult(status -> referenceCountService.release(content.getId()));
        storage.presentKeys.add(content.getObjectKey());
        return content;
    }

    private long refCount(AssetContent content) {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.findByIdForUpdate(content.getId()).orElseThrow().getRefCount());
    }

    /** Runs a hook once, before the first read-write transaction begins. */
    private static final class HookingTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private Runnable beforeFirstWrite;

        private HookingTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            if (definition != null && !definition.isReadOnly() && beforeFirstWrite != null) {
                Runnable hook = beforeFirstWrite;
                beforeFirstWrite = null;
                hook.run();
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
        private final Set<String> presentKeys = new HashSet<>();
        private final Set<String> failingKeys = new HashSet<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private boolean interruptAfterDelete;

        @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
        @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }

        @Override
        public void delete(String objectKey) {
            if (failingKeys.contains(objectKey)) {
                throw new BlobStorageException("delete failed for " + objectKey);
            }
            presentKeys.remove(objectKey);
            deletedKeys.add(objectKey);
            if (interruptAfterDelete) {
                interruptAfterDelete = false;
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean exists(String objectKey) {
            return presentKeys.contains(objectKey);
        }
    }

    private static final class RecordingHistory implements UploadHistory {
        private final List<String> failedObjectKeys = new ArrayList<>();
        private boolean failRecording;

        @Override
        public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) {
            if (failRecording) {
                throw new IllegalStateException("history unavailable");
            }
            failedObjectKeys.add(objectKey);
        }

        @Override public void recordUpload(UploadContribution contribution) { throw new UnsupportedOperationException(); }
        @Override public HistoryTotals totals() { throw new UnsupportedOperationException(); }
        @Override public List<FailureRecord> recentFailures(Instant since, int limit) { throw new UnsupportedOperationException(); }
        @Override public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) { throw new UnsupportedOperationException(); }
        @Override public boolean persistent() { return false; }
    }
}
