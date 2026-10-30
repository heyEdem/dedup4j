package com.edem.dedup4j.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Runs storage cleanup on a cron schedule: the orphaned object sweep, when configured, then the
 * released object pass, which also catches an object a late put wrote back behind a released row.
 * A failed orphan sweep is logged and the released pass still runs; the schedule carries on after
 * any failure. Stopping interrupts a scheduled pass, which ends before its next row or object; a
 * {@link #runNow()} on another thread is not stopped.
 *
 * <p>Every instance runs its own schedule. That is safe, since each deletion happens under the
 * content row's lock, but repeats the listing and reads per instance.</p>
 */
public final class ScheduledCleanup implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledCleanup.class);
    private static final int SHUTDOWN_SECONDS = 30;

    private final ReleasedObjectCleanup releasedCleanup;
    private final OrphanedObjectSweep orphanSweep;
    private final CronTrigger trigger;
    private final Object lifecycleMonitor = new Object();
    private ThreadPoolTaskScheduler scheduler;

    /** @param orphanSweep null when storage cannot list objects or no key prefix is set */
    public ScheduledCleanup(ReleasedObjectCleanup releasedCleanup, OrphanedObjectSweep orphanSweep, CronTrigger trigger) {
        this.releasedCleanup = Objects.requireNonNull(releasedCleanup, "releasedCleanup must not be null");
        this.orphanSweep = orphanSweep;
        this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
    }

    /**
     * Runs one cleanup on the calling thread, outside any transaction.
     *
     * @throws CancellationException when the thread is interrupted
     * @throws RuntimeException      from the released pass, or from the orphan sweep after the released
     *                               pass has run
     */
    public void runNow() {
        RuntimeException sweepFailure = null;
        if (orphanSweep != null) {
            try {
                orphanSweep.run();
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                LOGGER.warn("event=cleanup.orphans.failed errorType={} failureMessage={} "
                                + "detail=\"released object pass runs anyway\"",
                        failure.getClass().getSimpleName(), failure.getMessage(), failure);
                sweepFailure = failure;
            }
        }
        releasedCleanup.run();
        if (sweepFailure != null) {
            throw sweepFailure;
        }
    }

    public boolean sweepsOrphans() {
        return orphanSweep != null;
    }

    public String cronExpression() {
        return trigger.getExpression();
    }

    private void runScheduled() {
        try {
            runNow();
        } catch (CancellationException cancelled) {
            LOGGER.info("event=cleanup.run.cancelled");
        } catch (RuntimeException failure) {
            LOGGER.warn("event=cleanup.run.failed errorType={} failureMessage={}",
                    failure.getClass().getSimpleName(), failure.getMessage(), failure);
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (scheduler != null) {
                return;
            }
            ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("dedup4j-cleanup-");
            taskScheduler.setDaemon(true);
            taskScheduler.setAwaitTerminationSeconds(SHUTDOWN_SECONDS);
            taskScheduler.initialize();
            taskScheduler.schedule(this::runScheduled, trigger);
            scheduler = taskScheduler;
        }
        LOGGER.info("event=cleanup.scheduled cron=\"{}\" orphanSweep={}", trigger.getExpression(), sweepsOrphans());
    }

    /** Interrupts a running pass and waits up to 30 seconds for it to end. */
    @Override
    public void stop() {
        ThreadPoolTaskScheduler taskScheduler;
        synchronized (lifecycleMonitor) {
            taskScheduler = scheduler;
            scheduler = null;
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycleMonitor) {
            return scheduler != null;
        }
    }
}
