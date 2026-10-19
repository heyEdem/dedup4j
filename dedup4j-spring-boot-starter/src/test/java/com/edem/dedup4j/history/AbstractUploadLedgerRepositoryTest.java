package com.edem.dedup4j.history;

import com.edem.dedup4j.historystore.FailureEvent;
import com.edem.dedup4j.historystore.FailureLogRepository;
import com.edem.dedup4j.historystore.HistoryDataset;
import com.edem.dedup4j.historystore.LedgerTotals;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.historystore.UploadEvent;
import com.edem.dedup4j.historystore.UploadLedgerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository behavior against a freshly migrated history schema. Subclasses choose the database
 * (H2 here, PostgreSQL in a Testcontainers subclass) by supplying a JDBC URL and credentials.
 */
abstract class AbstractUploadLedgerRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private EntityManagerFactory emf;

    /** A JDBC URL pointing at an empty database that no other test uses. */
    protected abstract String jdbcUrl();

    protected abstract String jdbcUser();

    protected abstract String jdbcPassword();

    @BeforeEach
    void migrateAndOpen() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUser(), jdbcPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            for (String changelog : new String[] {
                    "db/dedup4j/changes/001-create-asset-content.yaml",
                    "db/dedup4j/history/db.changelog-history.yaml"}) {
                new Liquibase(changelog, new ClassLoaderResourceAccessor(), database).update("");
            }
        }
        emf = new Configuration()
                .addAnnotatedClass(HistoryDataset.class)
                .addAnnotatedClass(UploadEvent.class)
                .addAnnotatedClass(FailureEvent.class)
                .setProperty("jakarta.persistence.jdbc.url", jdbcUrl())
                .setProperty("jakarta.persistence.jdbc.user", jdbcUser())
                .setProperty("jakarta.persistence.jdbc.password", jdbcPassword())
                .buildSessionFactory();
    }

    @AfterEach
    void close() {
        emf.close();
    }

    @Test
    void emptyLedgerIsZero() {
        assertThat(totals()).isEqualTo(LedgerTotals.ZERO);
    }

    @Test
    void totalsAccumulate() {
        List<Long> sequences = new ArrayList<>();
        runInTx(em -> {
            UploadLedgerRepository ledger = new UploadLedgerRepository(em);
            sequences.add(ledger.append(completed(false, true, 100), "i", NOW).getSequenceNo());
            sequences.add(ledger.append(completed(true, false, 100), "i", NOW).getSequenceNo());
            sequences.add(ledger.append(completed(true, true, 50), "i", NOW).getSequenceNo());
        });
        assertThat(sequences).containsExactly(1L, 2L, 3L);
        assertThat(totals())
                .isEqualTo(new LedgerTotals(3, 2, 2, 250, 150));
    }

    @Test
    void compensationCancelsOriginal() {
        UploadEvent compensation = inTx(em -> {
            UploadLedgerRepository ledger = new UploadLedgerRepository(em);
            UploadEvent original = ledger.append(completed(false, true, 100), "i", NOW);
            return ledger.append(UploadContribution.compensating(original), "i", NOW);
        });
        assertThat(compensation.getCompensatesSequenceNo()).isEqualTo(1L);
        assertThat(totals()).isEqualTo(LedgerTotals.ZERO);
    }

    @Test
    void delayedCommitIsNotSkipped() throws Exception {
        List<UploadEvent> events = delayedPair(
                em -> new UploadLedgerRepository(em).append(completed(false, true, 10), "a", NOW),
                em -> new UploadLedgerRepository(em).append(completed(false, true, 10), "b", NOW));
        assertThat(events.get(0).getSequenceNo()).isEqualTo(1L);
        assertThat(events.get(1).getSequenceNo()).isEqualTo(2L);
        assertThat(events.get(1).getTotalUploads()).isEqualTo(2L);
        assertThat(totals().uploads()).isEqualTo(2L);
    }

    @Test
    void failurePagingReturnsEachRowOnce() {
        runInTx(em -> {
            FailureLogRepository failures = new FailureLogRepository(em);
            for (int i = 0; i < 5; i++) {
                failures.append("i", NOW, "upload", null, null, "boom " + i);
            }
        });
        assertThat(pageAll(2)).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void delayedFailureCommitIsNotSkipped() throws Exception {
        List<FailureEvent> events = delayedPair(
                em -> new FailureLogRepository(em).append("a", NOW, "upload", null, null, "A"),
                em -> new FailureLogRepository(em).append("b", NOW, "upload", null, null, "B"));
        assertThat(events.get(0).getSequenceNo()).isEqualTo(1L);
        assertThat(events.get(1).getSequenceNo()).isEqualTo(2L);
        List<FailureEvent> firstPage = inTx(em -> new FailureLogRepository(em).pageAfter(0, 10));
        assertThat(firstPage).extracting(FailureEvent::getMessage).containsExactly("A", "B");
    }

    private LedgerTotals totals() {
        return inTx(em -> new UploadLedgerRepository(em).latestTotals());
    }

    private List<Long> pageAll(int limit) {
        List<Long> seen = new ArrayList<>();
        long cursor = 0;
        while (true) {
            long after = cursor;
            List<FailureEvent> page = inTx(em -> new FailureLogRepository(em).pageAfter(after, limit));
            if (page.isEmpty()) {
                return seen;
            }
            page.forEach(f -> seen.add(f.getSequenceNo()));
            cursor = page.get(page.size() - 1).getSequenceNo();
        }
    }

    /**
     * Thread A appends and holds its transaction open for 500 ms; thread B starts 100 ms later and
     * must wait on the dataset row lock. Returns A's and B's appended entities.
     */
    private <T> List<T> delayedPair(Function<EntityManager, T> a, Function<EntityManager, T> b)
            throws Exception {
        CountDownLatch aAppended = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> first = executor.submit((Callable<T>) () -> {
                EntityManager em = emf.createEntityManager();
                try {
                    em.getTransaction().begin();
                    T result = a.apply(em);
                    aAppended.countDown();
                    Thread.sleep(500);
                    em.getTransaction().commit();
                    return result;
                } finally {
                    em.close();
                }
            });
            assertThat(aAppended.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            Future<T> second = executor.submit((Callable<T>) () -> inTx(b));
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T inTx(Function<EntityManager, T> work) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            T result = work.apply(em);
            em.getTransaction().commit();
            return result;
        } finally {
            em.close();
        }
    }

    private void runInTx(Consumer<EntityManager> work) {
        inTx(em -> {
            work.accept(em);
            return null;
        });
    }

    private static UploadContribution completed(boolean duplicate, boolean physicalWrite, long size) {
        return UploadContribution.completed(UUID.randomUUID(), "SHA-256", "h", size, duplicate, physicalWrite);
    }
}
