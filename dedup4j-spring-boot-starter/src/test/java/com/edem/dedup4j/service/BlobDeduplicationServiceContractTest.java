package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobDeduplicationServiceContractTest {

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @Test
    void exposesStorageNeutralServiceMethods() throws NoSuchMethodException {
        assertEquals(BlobReference.class,
                BlobDeduplicationService.class.getMethod("store", StoreBlobCommand.class).getReturnType());
        assertEquals(void.class,
                BlobDeduplicationService.class.getMethod("retain", UUID.class).getReturnType());
        assertEquals(void.class,
                BlobDeduplicationService.class.getMethod("release", UUID.class).getReturnType());
        assertEquals(BlobResource.class,
                BlobDeduplicationService.class.getMethod("get", UUID.class).getReturnType());
        assertEquals(BlobLocation.class,
                BlobDeduplicationService.class.getMethod("location", UUID.class).getReturnType());

        for (Method method : BlobDeduplicationService.class.getDeclaredMethods()) {
            assertEquals(false, method.getReturnType().getName().startsWith("software.amazon.awssdk"));
            assertEquals(false, method.getReturnType().getName().startsWith("com.azure"));
        }
    }

    @Test
    void locationFindsPersistedContentWithoutStorageIo() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        try {
            NoOpBlobStorage storage = new NoOpBlobStorage();
            AssetContentRepository repository = new AssetContentRepository(entityManager);
            AssetContent content = new AssetContent(
                    "sha-256", "abc", 3, "sha-256/ab/abc", "s3", "images", "text/plain", "txt"
            );
            entityManager.persist(content);
            entityManager.flush();

            DefaultBlobDeduplicationService service = new DefaultBlobDeduplicationService(
                    repository,
                    new ReferenceCountService(repository, storage),
                    new AssetContentMutationService(entityManager),
                    storage,
                    new Sha256ContentHasher(),
                    new HashObjectKeyStrategy("")
            );

            assertEquals(new BlobLocation("s3", "images", "sha-256/ab/abc"),
                    service.location(content.getId()));
            assertFalse(storage.ioCalled);
        } finally {
            entityManager.getTransaction().rollback();
            entityManager.close();
        }
    }

    @Test
    void missingContentFailsWithProviderNeutralException() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        try {
            BlobStorage storage = new NoOpBlobStorage();
            DefaultBlobDeduplicationService service = new DefaultBlobDeduplicationService(
                    new AssetContentRepository(entityManager),
                    new ReferenceCountService(new AssetContentRepository(entityManager), storage),
                    new AssetContentMutationService(entityManager),
                    storage,
                    new Sha256ContentHasher(),
                    new HashObjectKeyStrategy("")
            );
            UUID missingId = UUID.randomUUID();

            assertThrows(ContentNotFoundException.class, () -> service.retain(missingId));
            assertThrows(ContentNotFoundException.class, () -> service.release(missingId));
            assertThrows(ContentNotFoundException.class, () -> service.get(missingId));
        } finally {
            entityManager.getTransaction().rollback();
            entityManager.close();
        }
    }

    private static final class NoOpBlobStorage implements BlobStorage {

        private boolean ioCalled;

        @Override
        public StoredBlob put(PutBlobRequest request) {
            ioCalled = true;
            throw new UnsupportedOperationException();
        }

        @Override
        public BlobResource get(String objectKey) {
            ioCalled = true;
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            ioCalled = true;
        }

        @Override
        public boolean exists(String objectKey) {
            ioCalled = true;
            return false;
        }
    }
}
