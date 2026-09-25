package com.edem.blobhelper.jpa;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.exception.ReferenceCountUnderflowException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceCountServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private RecordingBlobStorage storage;
    private ReferenceCountService service;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("blob-helper-jpa-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        storage = new RecordingBlobStorage();
        service = new ReferenceCountService(new AssetContentRepository(entityManager), storage);
    }

    @AfterEach
    void closeEntityManager() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void retainIncrementsReferenceCountOnce() {
        AssetContent content = persistContent();

        entityManager.clear();
        entityManager.getTransaction().begin();

        service.retain(content.getId());

        entityManager.getTransaction().commit();
        entityManager.clear();

        assertEquals(2L, entityManager.find(AssetContent.class, content.getId()).getRefCount());
    }

    @Test
    void releaseDecrementsReferenceCountOnce() {
        AssetContent content = persistContent();

        entityManager.clear();
        entityManager.getTransaction().begin();
        service.retain(content.getId());
        entityManager.getTransaction().commit();

        entityManager.clear();
        entityManager.getTransaction().begin();
        service.release(content.getId());
        entityManager.getTransaction().commit();
        entityManager.clear();

        assertEquals(1L, entityManager.find(AssetContent.class, content.getId()).getRefCount());
        assertEquals(List.of(), storage.deletedObjectKeys());
    }

    @Test
    void releaseFinalReferenceDeletesPhysicalObjectOnce() {
        AssetContent content = persistContent();

        entityManager.clear();
        entityManager.getTransaction().begin();
        service.release(content.getId());
        entityManager.getTransaction().commit();
        entityManager.clear();

        assertEquals(0L, entityManager.find(AssetContent.class, content.getId()).getRefCount());
        assertEquals(List.of(content.getObjectKey()), storage.deletedObjectKeys());
    }

    @Test
    void releaseRejectsReferenceCountUnderflow() {
        AssetContent content = persistContent();

        entityManager.clear();
        entityManager.getTransaction().begin();
        service.release(content.getId());

        assertThrows(
                ReferenceCountUnderflowException.class,
                () -> service.release(content.getId())
        );

        entityManager.getTransaction().commit();
        entityManager.clear();

        assertEquals(0L, entityManager.find(AssetContent.class, content.getId()).getRefCount());
        assertEquals(List.of(content.getObjectKey()), storage.deletedObjectKeys());
    }

    @Test
    void retainFailsWhenContentDoesNotExist() {
        entityManager.getTransaction().begin();

        assertThrows(
                ContentNotFoundException.class,
                () -> service.retain(UUID.randomUUID())
        );

        entityManager.getTransaction().rollback();
    }

    private AssetContent persistContent() {
        String contentHash = UUID.randomUUID().toString().replace("-", "");
        AssetContent content = new AssetContent(
                "SHA-256",
                contentHash,
                42L,
                "uploads/" + contentHash,
                "local",
                "test-bucket",
                "application/octet-stream",
                "bin"
        );

        entityManager.getTransaction().begin();
        entityManager.persist(content);
        entityManager.getTransaction().commit();
        return content;
    }

    private static final class RecordingBlobStorage implements BlobStorage {

        private final List<String> deletedObjectKeys = new ArrayList<>();

        @Override
        public StoredBlob put(PutBlobRequest request) {
            throw new UnsupportedOperationException("put is not used by this test");
        }

        @Override
        public BlobResource get(String objectKey) {
            throw new UnsupportedOperationException("get is not used by this test");
        }

        @Override
        public void delete(String objectKey) {
            deletedObjectKeys.add(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            throw new UnsupportedOperationException("exists is not used by this test");
        }

        private List<String> deletedObjectKeys() {
            return List.copyOf(deletedObjectKeys);
        }
    }
}
