package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-10-26T03:00:00Z");
    private static final CronTrigger NIGHTLY = new CronTrigger("0 0 3 * * *", ZoneOffset.UTC);
    private static final CronTrigger EVERY_SECOND = new CronTrigger("* * * * * *", ZoneOffset.UTC);

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private ReconciliationService service;
    private SimpleMeterRegistry registry;
    private ScheduledReconciliation scheduled;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createService() {
        entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        entityManager.createQuery("delete from AssetContent").executeUpdate();
        entityManager.getTransaction().commit();
        service = new ReconciliationService(new AssetContentRepository(entityManager));
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stopScheduler() {
        if (scheduled != null) {
            scheduled.stop();
        }
        entityManager.close();
    }

    @Test
    void runNowRecordsTheSummaryAndMetrics() {
        AssetContent drifted = persistContent("a".repeat(64));
        persistContent("b".repeat(64));
        entityManager.clear();
        scheduled = scheduled(() -> Map.of(drifted.getId(), 4L), NIGHTLY);

        assertThat(scheduled.lastRun()).isEmpty();
        ReconciliationRun run = scheduled.runNow();

        assertThat(run.outcome()).isEqualTo(ReconciliationRun.Outcome.SUCCEEDED);
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.finishedAt()).isEqualTo(NOW);
        assertThat(run.summary().checkedContentCount()).isEqualTo(2);
        assertThat(run.summary().mismatchCount()).isEqualTo(2);
        assertThat(run.failureType()).isNull();
        assertThat(scheduled.lastRun()).contains(run);
        assertThat(registry.get("dedup4j.reconciliation.runs").tag("outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("dedup4j.reconciliation.mismatches").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("dedup4j.reconciliation.dangling").gauge().value()).isZero();
    }

    @Test
    void failedRunIsRecordedInsteadOfThrown() {
        scheduled = scheduled(() -> {
            throw new IllegalStateException("source down");
        }, NIGHTLY);

        ReconciliationRun run = scheduled.runNow();

        assertThat(run.outcome()).isEqualTo(ReconciliationRun.Outcome.FAILED);
        assertThat(run.summary()).isNull();
        assertThat(run.failureType()).isEqualTo("IllegalStateException");
        assertThat(run.failureMessage()).isEqualTo("source down");
        assertThat(scheduled.lastRun()).contains(run);
        assertThat(registry.get("dedup4j.reconciliation.runs").tag("outcome", "failure").timer().count())
                .isEqualTo(1);
    }

    @Test
    void lifecycleStartsAndStopsTheSchedule() {
        AtomicInteger calls = new AtomicInteger();
        scheduled = scheduled(() -> {
            calls.incrementAndGet();
            return Map.of();
        }, EVERY_SECOND);

        assertThat(scheduled.isRunning()).isFalse();
        scheduled.start();
        assertThat(scheduled.isRunning()).isTrue();
        awaitTrue(() -> scheduled.lastRun().isPresent());
        assertThat(scheduled.lastRun().orElseThrow().outcome()).isEqualTo(ReconciliationRun.Outcome.SUCCEEDED);
        scheduled.stop();

        assertThat(scheduled.isRunning()).isFalse();
        int callsAtStop = calls.get();
        sleep(Duration.ofMillis(1500));
        assertThat(calls.get()).isEqualTo(callsAtStop);
    }

    @Test
    void stopInterruptsAndWaitsForTheActiveRun() {
        CountDownLatch running = new CountDownLatch(1);
        scheduled = scheduled(() -> {
            running.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                // Takes a while to wind down, so stop() passes only if it waits for the run.
                long windDownEnds = System.nanoTime() + Duration.ofMillis(500).toNanos();
                while (System.nanoTime() < windDownEnds) {
                    Thread.onSpinWait();
                }
                throw new CancellationException("interrupted");
            }
            return Map.of();
        }, EVERY_SECOND);
        scheduled.start();
        awaitTrue(() -> running.getCount() == 0);

        Instant stopCalled = Instant.now();
        scheduled.stop();

        assertThat(Duration.between(stopCalled, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(scheduled.lastRun()).hasValueSatisfying(run ->
                assertThat(run.failureType()).isEqualTo("CancellationException"));
    }

    @Test
    void stopInterruptsAManualRunEvenWithACronRunWaitingBehindIt() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        scheduled = scheduled(() -> {
            running.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                long windDownEnds = System.nanoTime() + Duration.ofMillis(500).toNanos();
                while (System.nanoTime() < windDownEnds) {
                    Thread.onSpinWait();
                }
                throw new CancellationException("interrupted");
            }
            return Map.of();
        }, EVERY_SECOND);
        Thread manual = new Thread(scheduled::runNow);
        manual.start();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        scheduled.start();
        sleep(Duration.ofMillis(1500)); // a cron run is now waiting behind the manual run

        Instant stopCalled = Instant.now();
        scheduled.stop();

        assertThat(Duration.between(stopCalled, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(scheduled.lastRun()).hasValueSatisfying(run ->
                assertThat(run.failureType()).isEqualTo("CancellationException"));
        manual.join(5000);
    }

    @Test
    void aManualRunWaitingWhenStopBeginsIsRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        scheduled = scheduled(() -> {
            calls.incrementAndGet();
            running.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                throw new CancellationException("interrupted");
            }
            return Map.of();
        }, NIGHTLY);
        Thread first = new Thread(scheduled::runNow);
        first.start();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Throwable> secondOutcome = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                scheduled.runNow();
            } catch (Throwable rejected) {
                secondOutcome.set(rejected);
            }
        });
        second.start();
        awaitTrue(() -> second.getState() == Thread.State.WAITING);

        scheduled.stop();
        first.join(5000);
        second.join(5000);

        assertThat(calls).hasValue(1);
        assertThat(secondOutcome.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Reconciliation is stopping");
    }

    @Test
    void failingRunsDoNotCancelTheSchedule() {
        AtomicInteger calls = new AtomicInteger();
        scheduled = scheduled(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("source down");
        }, EVERY_SECOND);

        scheduled.start();

        awaitTrue(() -> calls.get() >= 2);
    }

    private ScheduledReconciliation scheduled(LogicalReferenceCountSource source, CronTrigger trigger) {
        return new ScheduledReconciliation(
                service, source, trigger, 2, 10, new Dedup4jMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));
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

    private static void awaitTrue(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 5 seconds");
            }
            sleep(Duration.ofMillis(50));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
