package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.Persistence;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class ReconciliationServiceTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private AssetContentRepository repository;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory(
                "dedup4j-starter-test", Map.of("hibernate.generate_statistics", "true"));
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
    void summarizePagesThroughEveryContentRow() {
        List<AssetContent> contents = persistContents(5);
        entityManager.clear();
        Map<UUID, Long> counts = new HashMap<>();
        counts.put(contents.get(0).getId(), 1L);
        counts.put(contents.get(1).getId(), 3L);
        counts.put(contents.get(2).getId(), 1L);
        counts.put(contents.get(4).getId(), 2L);

        ReconciliationSummary summary = new ReconciliationService(repository).summarize(() -> counts, 2, 10);

        assertThat(summary.checkedContentCount()).isEqualTo(5);
        assertThat(summary.mismatchCount()).isEqualTo(3);
        assertThat(summary.mismatchSample())
                .extracting(ReconciliationMismatch::assetContentId,
                        ReconciliationMismatch::expectedReferenceCount,
                        ReconciliationMismatch::actualReferenceCount)
                .containsExactlyInAnyOrder(
                        tuple(contents.get(1).getId(), 3L, 1L),
                        tuple(contents.get(3).getId(), 0L, 1L),
                        tuple(contents.get(4).getId(), 2L, 1L));
        assertThat(summary.danglingReferenceCount()).isZero();
    }

    @Test
    void summarizeCountsEveryMismatchButKeepsABoundedSample() {
        persistContents(4);
        entityManager.clear();

        ReconciliationSummary summary = new ReconciliationService(repository).summarize(Map::of, 3, 2);

        assertThat(summary.checkedContentCount()).isEqualTo(4);
        assertThat(summary.mismatchCount()).isEqualTo(4);
        assertThat(summary.mismatchSample()).hasSize(2);
    }

    @Test
    void summarizeReportsReferencesToUnknownContent() {
        AssetContent known = persistContent("e".repeat(64));
        entityManager.clear();
        UUID dangling = UUID.randomUUID();
        UUID zeroCount = UUID.randomUUID();

        ReconciliationSummary summary = new ReconciliationService(repository).summarize(
                () -> Map.of(known.getId(), 1L, dangling, 2L, zeroCount, 0L), 10, 10);

        assertThat(summary.mismatchCount()).isZero();
        assertThat(summary.danglingReferenceCount()).isEqualTo(1);
        assertThat(summary.danglingReferenceSample()).containsExactly(dangling);
    }

    @Test
    void summarizeRejectsIncompleteSourceResults() {
        persistContent("9".repeat(64));
        entityManager.clear();
        ReconciliationService service = new ReconciliationService(repository);
        Map<UUID, Long> negativeForUnknown = Map.of(UUID.randomUUID(), -1L);

        assertThatThrownBy(() -> service.summarize(() -> null, 10, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("logical reference counts must not be null");
        assertThatThrownBy(() -> service.summarize(() -> negativeForUnknown, 10, 10))
                .isInstanceOf(BlobValidationException.class);
    }

    @Test
    void summarizeStopsBetweenPagesWhenInterrupted() {
        persistContents(3);
        entityManager.clear();
        AtomicInteger queries = new AtomicInteger();
        ReconciliationService service = new ReconciliationService(
                new AssetContentRepository(interruptingAfterFirstQuery(entityManager, queries)));

        try {
            assertThatThrownBy(() -> service.summarize(Map::of, 1, 10))
                    .isInstanceOf(CancellationException.class)
                    .hasMessage("Reconciliation interrupted after 1 content rows");
        } finally {
            Thread.interrupted();
        }
        assertThat(queries).hasValue(1);
    }

    /** Interrupts the calling thread once the first query's results have been read. */
    private static EntityManager interruptingAfterFirstQuery(EntityManager target, AtomicInteger queries) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    if (method.getName().equals("createQuery") && result instanceof TypedQuery<?> query) {
                        queries.incrementAndGet();
                        return interruptingAfterResults(query);
                    }
                    return result;
                });
    }

    private static TypedQuery<?> interruptingAfterResults(TypedQuery<?> target) {
        return (TypedQuery<?>) Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),
                new Class<?>[]{TypedQuery.class}, (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    if (method.getName().equals("getResultList")) {
                        Thread.currentThread().interrupt();
                    }
                    return result == target ? proxy : result;
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void summarizeNeverWritesInsideACommittedTransaction() {
        persistContents(3);
        entityManager.clear();
        ReconciliationService service = new ReconciliationService(repository);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        entityManager.getTransaction().begin();
        ReconciliationSummary summary = service.summarize(Map::of, 2, 10);
        entityManager.getTransaction().commit();

        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        assertThat(summary.mismatchCount()).isEqualTo(3);
    }

    private List<AssetContent> persistContents(int count) {
        List<AssetContent> contents = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            contents.add(persistContent("%064x".formatted(index + 1)));
        }
        return contents;
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
}
