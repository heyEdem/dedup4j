package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentUploadIntegrationTest {

    private static final int WORKER_COUNT = 2;
    private static final String CONTENT_HASH = "d".repeat(64);

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-jpa-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @Test
    void concurrentDuplicatesCreateOneRow() throws Exception {
        CountDownLatch workersReady = new CountDownLatch(WORKER_COUNT);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        List<Future<UUID>> results = new ArrayList<>();

        try {
            for (int worker = 0; worker < WORKER_COUNT; worker++) {
                results.add(executor.submit(
                        () -> createOrRetainInOwnTransaction(workersReady, startWorkers)
                ));
            }

            assertTrue(workersReady.await(5L, TimeUnit.SECONDS));
            startWorkers.countDown();

            Set<UUID> contentIds = new HashSet<>();
            for (Future<UUID> result : results) {
                contentIds.add(result.get(10L, TimeUnit.SECONDS));
            }

            assertEquals(1, contentIds.size());
        } finally {
            startWorkers.countDown();
            executor.shutdownNow();
        }

        verifyConvergedContent();
    }

    private UUID createOrRetainInOwnTransaction(
            CountDownLatch workersReady,
            CountDownLatch startWorkers
    ) throws InterruptedException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            workersReady.countDown();
            if (!startWorkers.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent upload start");
            }

            entityManager.getTransaction().begin();
            AssetContent candidate = newContent();
            AssetContent content;
            try {
                content = new AssetContentMutationService(entityManager).createOrRetain(candidate);
            } catch (DuplicateContentIdentityException race) {
                entityManager.getTransaction().rollback();
                entityManager.getTransaction().begin();
                content = new AssetContentMutationService(entityManager).createOrRetain(candidate);
            }
            entityManager.getTransaction().commit();
            return content.getId();
        } catch (RuntimeException | Error failure) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw failure;
        } finally {
            entityManager.close();
        }
    }

    private void verifyConvergedContent() {
        EntityManager verifier = entityManagerFactory.createEntityManager();
        try {
            long rowCount = verifier.createQuery("""
                            select count(content)
                            from AssetContent content
                            where content.hashAlgorithm = :hashAlgorithm
                              and content.contentHash = :contentHash
                              and content.sizeBytes = :sizeBytes
                            """, Long.class)
                    .setParameter("hashAlgorithm", "SHA-256")
                    .setParameter("contentHash", CONTENT_HASH)
                    .setParameter("sizeBytes", 42L)
                    .getSingleResult();

            long referenceCount = verifier.createQuery("""
                            select content.refCount
                            from AssetContent content
                            where content.hashAlgorithm = :hashAlgorithm
                              and content.contentHash = :contentHash
                              and content.sizeBytes = :sizeBytes
                            """, Long.class)
                    .setParameter("hashAlgorithm", "SHA-256")
                    .setParameter("contentHash", CONTENT_HASH)
                    .setParameter("sizeBytes", 42L)
                    .getSingleResult();

            assertEquals(1L, rowCount);
            assertEquals((long) WORKER_COUNT, referenceCount);
        } finally {
            verifier.close();
        }
    }

    private AssetContent newContent() {
        return new AssetContent(
                "SHA-256",
                CONTENT_HASH,
                42L,
                "uploads/" + CONTENT_HASH,
                "local",
                "test-bucket",
                "application/octet-stream",
                "bin"
        );
    }
}
