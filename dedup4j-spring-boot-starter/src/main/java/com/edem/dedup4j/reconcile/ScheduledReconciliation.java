package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs a reconciliation pass on a cron trigger and keeps this instance's last result: a read-only
 * {@link ReconciliationService#summarize} in {@link ReconciliationMode#REPORT} mode, or a
 * {@link CoordinatedRepair#run} in {@link ReconciliationMode#REPAIR} mode.
 *
 * <p>The scheduler is private to this bean, so enabling reconciliation does not switch on
 * {@code @EnableScheduling} for the application. Its single thread and the cron trigger, which
 * computes the next time only after a run finishes, mean runs never overlap; {@link #runNow()} is
 * serialized with them; a report reads in the caller's transaction if one is active, while a repair
 * refuses one. A failed run is logged, counted and kept as the last result, and the
 * schedule carries on. {@link #stop()} rejects runs that have not started, interrupts an active
 * run, scheduled or manual, which ends at the next page or row, and waits up to {@value #SHUTDOWN_WAIT_SECONDS} seconds for it before returning; a
 * database call that ignores interrupts can outlast that wait. Enable it on one instance per shared database; nothing coordinates
 * instances.</p>
 */
public final class ScheduledReconciliation implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledReconciliation.class);
    private static final int SHUTDOWN_WAIT_SECONDS = 30;

    private final ReconciliationMode mode;
    private final ReconciliationService service;
    private final CoordinatedRepair repair;
    private final LogicalReferenceCountSource source;
    private final ContentReferenceCountSource authority;
    private final CronTrigger trigger;
    private final int pageSize;
    private final int sampleLimit;
    private final Dedup4jMetrics metrics;
    private final Clock clock;
    private final AtomicReference<ReconciliationRun> lastRun = new AtomicReference<>();
    private final Object lifecycleMonitor = new Object();
    private final ReentrantLock runLock = new ReentrantLock();
    private volatile Thread activeRun;
    private volatile boolean stopping;
    private ThreadPoolTaskScheduler scheduler;

    public ScheduledReconciliation(
            ReconciliationService service,
            LogicalReferenceCountSource source,
            CronTrigger trigger,
            int pageSize,
            int sampleLimit,
            Dedup4jMetrics metrics,
            Clock clock
    ) {
        this(ReconciliationMode.REPORT, Objects.requireNonNull(service, "service must not be null"), null,
                source, null, trigger, pageSize, sampleLimit, metrics, clock);
    }

    /** Repairs drift on the trigger; see {@link ReconciliationMode#REPAIR} for when that is safe. */
    public ScheduledReconciliation(
            CoordinatedRepair repair,
            LogicalReferenceCountSource source,
            ContentReferenceCountSource authority,
            CronTrigger trigger,
            Dedup4jMetrics metrics,
            Clock clock
    ) {
        this(ReconciliationMode.REPAIR, null, Objects.requireNonNull(repair, "repair must not be null"),
                source, Objects.requireNonNull(authority, "authority must not be null"), trigger, 1, 0, metrics, clock);
    }

    private ScheduledReconciliation(
            ReconciliationMode mode,
            ReconciliationService service,
            CoordinatedRepair repair,
            LogicalReferenceCountSource source,
            ContentReferenceCountSource authority,
            CronTrigger trigger,
            int pageSize,
            int sampleLimit,
            Dedup4jMetrics metrics,
            Clock clock
    ) {
        this.mode = mode;
        this.service = service;
        this.repair = repair;
        this.authority = authority;
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (sampleLimit < 0) {
            throw new IllegalArgumentException("sampleLimit must not be negative");
        }
        this.pageSize = pageSize;
        this.sampleLimit = sampleLimit;
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Runs a pass now, after any run in progress.
     *
     * @throws IllegalStateException if {@link #stop()} has begun, including for a call that was
     *                               waiting when it did, or, in repair mode, when called inside a
     *                               transaction, whose locks a scheduled repair could be waiting on
     */
    public ReconciliationRun runNow() {
        if (mode == ReconciliationMode.REPAIR && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A repair run must be started outside a transaction");
        }
        runLock.lock();
        try {
            if (!claimRun()) {
                throw new IllegalStateException("Reconciliation is stopping");
            }
            return run();
        } finally {
            activeRun = null;
            runLock.unlock();
        }
    }

    /** A cron run waits interruptibly behind a manual run, so shutdown can cancel it. */
    private void runScheduled() {
        try {
            runLock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (claimRun()) {
                run();
            }
        } finally {
            activeRun = null;
            runLock.unlock();
        }
    }

    /**
     * Records this thread as the active run, then checks for shutdown. {@link #stop()} sets
     * {@code stopping} before reading {@code activeRun}, so either this sees the flag or stop sees
     * this thread and interrupts it.
     */
    private boolean claimRun() {
        activeRun = Thread.currentThread();
        return !stopping;
    }

    private ReconciliationRun run() {
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        ReconciliationRun run;
        try {
            if (mode == ReconciliationMode.REPAIR) {
                // CoordinatedRepair logs its own completion; the drift gauges stay those of the last report.
                CoordinatedRepairResult result = repair.run(source, authority);
                run = ReconciliationRun.repaired(startedAt, clock.instant(), result);
                metrics.recordReconciliationRun(true, Duration.ofNanos(System.nanoTime() - startNanos));
            } else {
                ReconciliationSummary summary = service.summarize(source, pageSize, sampleLimit);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                run = ReconciliationRun.succeeded(startedAt, clock.instant(), summary);
                metrics.recordReconciliationRun(true, elapsed);
                metrics.recordReconciliationDrift(summary.mismatchCount(), summary.danglingReferenceCount());
                log(summary, elapsed);
            }
        } catch (CancellationException cancelled) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            run = ReconciliationRun.failed(startedAt, clock.instant(), mode, cancelled);
            metrics.recordReconciliationRun(false, elapsed);
            LOGGER.info("event=reconcile.run.cancelled durationMs={} reason={}", elapsed.toMillis(), cancelled.getMessage());
        } catch (RuntimeException failure) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            run = ReconciliationRun.failed(startedAt, clock.instant(), mode, failure);
            metrics.recordReconciliationRun(false, elapsed);
            LOGGER.error(
                    "event=reconcile.run.failed durationMs={} errorType={} failureMessage={}",
                    elapsed.toMillis(),
                    run.failureType(),
                    run.failureMessage(),
                    failure
            );
        }
        lastRun.set(run);
        return run;
    }

    public Optional<ReconciliationRun> lastRun() {
        return Optional.ofNullable(lastRun.get());
    }

    public ReconciliationMode mode() {
        return mode;
    }

    public String cronExpression() {
        return trigger.getExpression();
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (scheduler != null) {
                return;
            }
            stopping = false;
            ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("dedup4j-reconciliation-");
            taskScheduler.setDaemon(true);
            taskScheduler.initialize();
            taskScheduler.schedule(this::runScheduled, trigger);
            scheduler = taskScheduler;
        }
    }

    @Override
    public void stop() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_WAIT_SECONDS);
        stopping = true;
        Thread running = activeRun;
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
        }
        ThreadPoolTaskScheduler taskScheduler;
        synchronized (lifecycleMonitor) {
            taskScheduler = scheduler;
            scheduler = null;
        }
        try {
            if (taskScheduler != null) {
                taskScheduler.shutdown(); // interrupts the scheduler thread, including a cron run waiting for the lock
                taskScheduler.getScheduledExecutor().awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
            }
            if (runLock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) {
                runLock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycleMonitor) {
            return scheduler != null;
        }
    }

    private static void log(ReconciliationSummary summary, Duration elapsed) {
        if (summary.mismatchCount() == 0 && summary.danglingReferenceCount() == 0) {
            LOGGER.info(
                    "event=reconcile.run.completed checked={} mismatches=0 dangling=0 durationMs={}",
                    summary.checkedContentCount(),
                    elapsed.toMillis()
            );
            return;
        }
        LOGGER.warn(
                "event=reconcile.run.completed checked={} mismatches={} dangling={} durationMs={}",
                summary.checkedContentCount(),
                summary.mismatchCount(),
                summary.danglingReferenceCount(),
                elapsed.toMillis()
        );
        for (ReconciliationMismatch mismatch : summary.mismatchSample()) {
            LOGGER.warn(
                    "event=reconcile.mismatch contentId={} expected={} actual={}",
                    mismatch.assetContentId(),
                    mismatch.expectedReferenceCount(),
                    mismatch.actualReferenceCount()
            );
        }
        for (UUID contentId : summary.danglingReferenceSample()) {
            LOGGER.warn("event=reconcile.dangling contentId={}", contentId);
        }
    }
}
