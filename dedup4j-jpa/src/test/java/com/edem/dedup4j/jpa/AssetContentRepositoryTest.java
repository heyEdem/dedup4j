package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetContentRepositoryTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private AssetContentRepository repository;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-jpa-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        repository = new AssetContentRepository(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void findsContentByCompleteIdentity() {
        AssetContent content = persistContent("SHA-256", "a".repeat(64), 42L);

        entityManager.clear();

        Optional<AssetContent> found = repository.findByIdentity(
                "SHA-256",
                "a".repeat(64),
                42L
        );

        assertTrue(found.isPresent());
        assertEquals(content.getId(), found.orElseThrow().getId());
        assertFalse(repository.findByIdentity("SHA-256", "a".repeat(64), 43L).isPresent());
        assertFalse(repository.findByIdentity("SHA-512", "a".repeat(64), 42L).isPresent());
        assertFalse(repository.findByIdentity("SHA-256", "b".repeat(64), 42L).isPresent());
    }

    @Test
    void findsContentByIdWithPessimisticWriteLock() {
        AssetContent content = persistContent("SHA-256", "c".repeat(64), 7L);

        entityManager.clear();
        entityManager.getTransaction().begin();

        Optional<AssetContent> locked = repository.findByIdForUpdate(content.getId());

        assertTrue(locked.isPresent());
        AssetContent lockedContent = locked.orElseThrow();
        assertEquals(content.getId(), lockedContent.getId());
        assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(lockedContent));
        assertFalse(repository.findByIdForUpdate(UUID.randomUUID()).isPresent());
    }

    @Test
    void enforcesContentIdentityUniqueConstraint() {
        persistContent("SHA-256", "d".repeat(64), 11L);
        AssetContent duplicate = newAsset("SHA-256", "d".repeat(64), 11L);

        entityManager.getTransaction().begin();
        entityManager.persist(duplicate);

        assertThrows(RuntimeException.class, () -> entityManager.getTransaction().commit());
    }

    @Test
    void rejectsNullEntityManager() {
        assertThrows(NullPointerException.class, () -> new AssetContentRepository(null));
    }

    @Test
    void pagesReferencedContentByReferencesOrSize() {
        entityManager.getTransaction().begin();
        entityManager.createQuery("delete from AssetContent").executeUpdate();
        AssetContent small = persistWithReferences("c".repeat(64), 10L, 3);
        AssetContent large = persistWithReferences("d".repeat(64), 900L, 1);
        AssetContent middle = persistWithReferences("e".repeat(64), 50L, 2);
        persistWithReferences("f".repeat(64), 5000L, 0);
        entityManager.flush();

        assertEquals(3, repository.countReferenced());
        assertEquals(java.util.List.of(small.getId(), middle.getId(), large.getId()),
                ids(repository.findReferencedPage(ContentOrder.REFERENCES, 0, 10)));
        assertEquals(java.util.List.of(large.getId(), middle.getId()),
                ids(repository.findReferencedPage(ContentOrder.SIZE, 0, 2)));
        assertEquals(java.util.List.of(small.getId()),
                ids(repository.findReferencedPage(ContentOrder.SIZE, 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> repository.findReferencedPage(ContentOrder.SIZE, -1, 2));
    }

    private AssetContent persistWithReferences(String contentHash, long sizeBytes, int references) {
        AssetContent content = newAsset("SHA-256", contentHash, sizeBytes);
        for (int extra = 1; extra < references; extra++) {
            content.incrementRefCount();
        }
        if (references == 0) {
            content.decrementRefCount();
        }
        entityManager.persist(content);
        return content;
    }

    private static java.util.List<UUID> ids(java.util.List<AssetContent> contents) {
        return contents.stream().map(AssetContent::getId).toList();
    }

    private AssetContent persistContent(String hashAlgorithm, String contentHash, long sizeBytes) {
        AssetContent content = newAsset(hashAlgorithm, contentHash, sizeBytes);

        entityManager.getTransaction().begin();
        entityManager.persist(content);
        entityManager.getTransaction().commit();
        assertNotNull(content.getId());
        return content;
    }

    private AssetContent newAsset(String hashAlgorithm, String contentHash, long sizeBytes) {
        return new AssetContent(
                hashAlgorithm,
                contentHash,
                sizeBytes,
                "uploads/" + contentHash,
                "local",
                "test-bucket",
                "application/octet-stream",
                "bin"
        );
    }
}
