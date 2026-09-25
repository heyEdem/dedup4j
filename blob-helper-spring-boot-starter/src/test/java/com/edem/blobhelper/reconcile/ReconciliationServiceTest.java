package com.edem.blobhelper.reconcile;

import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private AssetContentRepository repository;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("blob-helper-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        repository = new AssetContentRepository(entityManager);
        entityManager.getTransaction().begin();
        entityManager.createQuery("delete from AssetContent").executeUpdate();
        entityManager.getTransaction().commit();
    }

    @AfterEach
    void closeEntityManager() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void reportsReferenceCountMismatchWithoutMutatingStoredCounts() {
        AssetContent expectedThree = persistContent("a".repeat(64));
        AssetContent expectedZero = persistContent("b".repeat(64));
        entityManager.clear();

        ReconciliationReport report = new ReconciliationService(repository).reconcile(
                () -> Map.of(expectedThree.getId(), 3L)
        );

        assertThat(report.checkedContentCount()).isEqualTo(2);
        assertThat(report.mismatches())
                .extracting(ReconciliationMismatch::assetContentId,
                        ReconciliationMismatch::expectedReferenceCount,
                        ReconciliationMismatch::actualReferenceCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(expectedThree.getId(), 3L, 1L),
                        org.assertj.core.groups.Tuple.tuple(expectedZero.getId(), 0L, 1L)
                );
        assertThat(entityManager.find(AssetContent.class, expectedThree.getId()).getRefCount()).isEqualTo(1L);
        assertThat(entityManager.find(AssetContent.class, expectedZero.getId()).getRefCount()).isEqualTo(1L);
    }

    @Test
    void repairsOnlyWhenExplicitlyEnabled() {
        AssetContent shouldIncrease = persistContent("c".repeat(64));
        AssetContent shouldReachZero = persistContent("d".repeat(64));
        entityManager.clear();

        RecordingStorage storage = new RecordingStorage();
        ReconciliationService service = new ReconciliationService(
                repository,
                new ReferenceCountService(repository, storage),
                true
        );

        entityManager.getTransaction().begin();
        ReconciliationReport report = service.repair(
                () -> Map.of(shouldIncrease.getId(), 3L)
        );
        entityManager.getTransaction().commit();

        assertThat(report.mismatches()).hasSize(2);
        assertThat(entityManager.find(AssetContent.class, shouldIncrease.getId()).getRefCount()).isEqualTo(3L);
        assertThat(entityManager.find(AssetContent.class, shouldReachZero.getId()).getRefCount()).isZero();
        assertThat(storage.deletedKeys).containsExactly(shouldReachZero.getObjectKey());
    }

    @Test
    void disabledRepairRemainsReadOnly() {
        AssetContent content = persistContent("e".repeat(64));
        entityManager.clear();

        ReconciliationService service = new ReconciliationService(
                repository,
                new ReferenceCountService(repository, new RecordingStorage()),
                false
        );

        entityManager.getTransaction().begin();
        ReconciliationReport report = service.repair(
                () -> Map.of(content.getId(), 3L)
        );
        entityManager.getTransaction().commit();

        assertThat(report.mismatches()).hasSize(1);
        assertThat(entityManager.find(AssetContent.class, content.getId()).getRefCount()).isEqualTo(1L);
    }

    private AssetContent persistContent(String hash) {
        AssetContent content = new AssetContent(
                "SHA-256", hash, 42L, "uploads/" + hash, "local", "test-bucket", null, "bin"
        );
        entityManager.getTransaction().begin();
        entityManager.persist(content);
        entityManager.getTransaction().commit();
        return content;
    }

    private static final class RecordingStorage implements BlobStorage {

        private final java.util.List<String> deletedKeys = new java.util.ArrayList<>();

        @Override
        public StoredBlob put(PutBlobRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlobResource get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            deletedKeys.add(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            return true;
        }
    }
}
