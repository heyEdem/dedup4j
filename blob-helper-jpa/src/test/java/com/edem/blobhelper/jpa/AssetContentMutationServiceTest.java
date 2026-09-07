package com.edem.blobhelper.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetContentMutationServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private AssetContentMutationService service;

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
        service = new AssetContentMutationService(entityManager);
    }

    @AfterEach
    void closeEntityManager() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void createsNewContentWithOneReference() {
        AssetContent candidate = newContent("a".repeat(64));

        entityManager.getTransaction().begin();
        AssetContent created = service.createOrRetain(candidate);
        entityManager.getTransaction().commit();
        entityManager.clear();

        AssetContent persisted = entityManager.find(AssetContent.class, created.getId());

        assertNotNull(persisted);
        assertEquals(1L, persisted.getRefCount());
    }

    @Test
    void retainsExistingContentOnceWithoutCreatingAnotherRow() {
        AssetContent existing = persistContent("b".repeat(64));
        AssetContent duplicate = newContent(existing.getContentHash());

        entityManager.clear();
        entityManager.getTransaction().begin();
        AssetContent retained = service.createOrRetain(duplicate);
        entityManager.getTransaction().commit();
        entityManager.clear();

        assertEquals(existing.getId(), retained.getId());
        assertEquals(2L, entityManager.find(AssetContent.class, existing.getId()).getRefCount());
        assertEquals(1L, countRows(existing.getContentHash()));
    }

    @Test
    void duplicateInsertEscapesForCallerRetryWithoutManagingTheTransaction() {
        AssetContent candidate = newContent("e".repeat(64));
        AtomicInteger transactionCalls = new AtomicInteger();
        AtomicInteger clearCalls = new AtomicInteger();
        EntityManager failingEntityManager = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("flush") && method.getParameterCount() == 0) {
                        throw new PersistenceException(new SQLException("duplicate", "23505"));
                    }
                    if (method.getName().equals("getTransaction")) {
                        transactionCalls.incrementAndGet();
                    }
                    if (method.getName().equals("clear")) {
                        clearCalls.incrementAndGet();
                    }
                    try {
                        return method.invoke(entityManager, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
        AssetContentMutationService failingService = new AssetContentMutationService(failingEntityManager);

        entityManager.getTransaction().begin();
        DuplicateContentIdentityException failure = assertThrows(
                DuplicateContentIdentityException.class,
                () -> failingService.createOrRetain(candidate)
        );

        assertEquals("SHA-256", failure.getHashAlgorithm());
        assertEquals(candidate.getContentHash(), failure.getContentHash());
        assertEquals(42L, failure.getSizeBytes());
        assertTrue(failure.getMessage().contains(candidate.getContentHash()));
        assertEquals(0, transactionCalls.get());
        assertEquals(0, clearCalls.get());
    }

    @Test
    void callerRollsBackAndRetriesDuplicateInsertByReloadingAndRetainingExistingContent()
            throws Exception {
        String contentHash = "c".repeat(64);
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();
        CountDownLatch secondFlushStarted = new CountDownLatch(1);
        EntityManager secondServiceEntityManager = signalFlush(
                secondEntityManager,
                secondFlushStarted
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            AssetContent firstCandidate = newContent(contentHash);
            firstEntityManager.getTransaction().begin();
            firstEntityManager.persist(firstCandidate);
            firstEntityManager.flush();

            secondEntityManager.getTransaction().begin();
            AssetContentMutationService secondService = new AssetContentMutationService(secondServiceEntityManager);
            Future<AssetContent> retryResult = executor.submit(
                    () -> secondService.createOrRetain(newContent(contentHash))
            );

            assertTrue(secondFlushStarted.await(5L, TimeUnit.SECONDS));
            firstEntityManager.getTransaction().commit();

            ExecutionException wrappedRace = assertThrows(ExecutionException.class, () -> get(retryResult));
            DuplicateContentIdentityException race = (DuplicateContentIdentityException) wrappedRace.getCause();
            assertEquals(contentHash, race.getContentHash());
            secondEntityManager.getTransaction().rollback();
            secondEntityManager.getTransaction().begin();
            AssetContent retained = new AssetContentMutationService(secondEntityManager)
                    .createOrRetain(newContent(contentHash));
            secondEntityManager.getTransaction().commit();

            assertEquals(firstCandidate.getId(), retained.getId());
            secondEntityManager.clear();
            AssetContent persisted = secondEntityManager.find(AssetContent.class, firstCandidate.getId());
            assertEquals(2L, persisted.getRefCount());
            assertEquals(1L, countRows(contentHash));
        } finally {
            executor.shutdownNow();
            if (firstEntityManager.getTransaction().isActive()) {
                firstEntityManager.getTransaction().rollback();
            }
            if (secondEntityManager.getTransaction().isActive()) {
                secondEntityManager.getTransaction().rollback();
            }
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    private EntityManager signalFlush(EntityManager delegate, CountDownLatch flushStarted) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("flush") && method.getParameterCount() == 0) {
                        flushStarted.countDown();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    private AssetContent get(Future<AssetContent> result)
            throws InterruptedException, ExecutionException, TimeoutException {
        return result.get(5L, TimeUnit.SECONDS);
    }

    private AssetContent persistContent(String contentHash) {
        AssetContent content = newContent(contentHash);

        entityManager.getTransaction().begin();
        entityManager.persist(content);
        entityManager.getTransaction().commit();
        return content;
    }

    private long countRows(String contentHash) {
        return entityManager.createQuery("""
                        select count(content)
                        from AssetContent content
                        where content.contentHash = :contentHash
                        """, Long.class)
                .setParameter("contentHash", contentHash)
                .getSingleResult();
    }

    private AssetContent newContent(String contentHash) {
        return new AssetContent(
                "SHA-256",
                contentHash,
                42L,
                "uploads/" + contentHash,
                "local",
                "test-bucket",
                "application/octet-stream",
                "bin"
        );
    }
}
