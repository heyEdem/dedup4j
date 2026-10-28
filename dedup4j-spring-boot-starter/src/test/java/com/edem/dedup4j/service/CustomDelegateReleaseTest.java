package com.edem.dedup4j.service;

import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The transactional wrapper must notice a release to zero made by a delegate it does not know. */
class CustomDelegateReleaseTest {

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @Test
    void deletesObjectWhenDelegateReleasesThroughBulkUpdateAfterLoadingTheRow() {
        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        AssetContentRepository repository = new AssetContentRepository(entityManager);
        RecordingStorage storage = new RecordingStorage();
        AssetContent content = new AssetContent(
                "sha-256", "b".repeat(63) + "1", 7L, "custom-delegate-key",
                "test", "bucket", "text/plain", "txt"
        );
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager.persist(content));

        BlobDeduplicationService bulkReleasing = new UnsupportedDelegate() {
            @Override
            public void release(UUID assetContentId) {
                repository.findById(assetContentId).orElseThrow();
                entityManager.createQuery("update AssetContent c set c.refCount = c.refCount - 1 where c.id = :id")
                        .setParameter("id", assetContentId)
                        .executeUpdate();
            }
        };
        SpringTransactionalBlobDeduplicationService service = new SpringTransactionalBlobDeduplicationService(
                bulkReleasing, repository, new ReferenceCountService(repository, storage), transactionManager
        );

        service.release(content.getId());

        assertThat(storage.deletedKeys).containsExactly("custom-delegate-key");
    }

    private abstract static class UnsupportedDelegate implements BlobDeduplicationService {
        @Override public BlobReference store(StoreBlobCommand command) { throw new UnsupportedOperationException(); }
        @Override public void retain(UUID id) { throw new UnsupportedOperationException(); }
        @Override public BlobResource get(UUID id) { throw new UnsupportedOperationException(); }
        @Override public BlobLocation location(UUID id) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingStorage implements BlobStorage {
        private final List<String> deletedKeys = new ArrayList<>();

        @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
        @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }
        @Override public void delete(String objectKey) { deletedKeys.add(objectKey); }
        @Override public boolean exists(String objectKey) { return false; }
    }
}
