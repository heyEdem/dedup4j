package com.edem.dedup4j.autoconfigure.cleanup;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.autoconfigure.service.Dedup4jServiceAutoConfiguration;
import com.edem.dedup4j.cleanup.OrphanedObjectSweep;
import com.edem.dedup4j.cleanup.ReleasedObjectCleanup;
import com.edem.dedup4j.cleanup.ScheduledCleanup;
import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.key.ObjectKeyStrategy;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Schedules storage cleanup unless {@code dedup4j.cleanup.enabled=false}. The orphaned object sweep
 * joins the schedule when the storage can list its objects and {@code dedup4j.storage.key-prefix} is
 * set; without a prefix it would scan the whole bucket, container or root, so it stays off.
 */
@AutoConfiguration(after = Dedup4jServiceAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "dedup4j.cleanup.enabled", matchIfMissing = true)
@ConditionalOnBean(ReleasedObjectCleanup.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jCleanupAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(Dedup4jCleanupAutoConfiguration.class);
    private static final String PREFIX = "dedup4j.cleanup.";

    @Bean
    @ConditionalOnMissingBean(ScheduledCleanup.class)
    ScheduledCleanup scheduledCleanup(
            Dedup4jProperties properties,
            ReleasedObjectCleanup releasedCleanup,
            BlobStorage storage,
            ContentHasher hasher,
            ObjectKeyStrategy keyStrategy,
            AssetContentRepository repository,
            AssetContentMutationService mutationService,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            ObjectProvider<UploadHistory> history,
            Dedup4jMetrics metrics
    ) {
        Dedup4jProperties.Cleanup cleanup = properties.getCleanup();
        String keyPrefix = properties.getStorage().getKeyPrefix();
        OrphanedObjectSweep sweep = null;
        if (!(storage instanceof ListableBlobStorage listable)) {
            LOGGER.info("event=cleanup.orphans.disabled reason=\"storage cannot list objects\"");
        } else if (keyPrefix == null || keyPrefix.isBlank()) {
            LOGGER.info("event=cleanup.orphans.disabled reason=\"set dedup4j.storage.key-prefix to sweep orphaned objects\"");
        } else {
            sweep = new OrphanedObjectSweep(listable, hasher, keyStrategy, repository, mutationService,
                    referenceCountService, transactionManager, history.getIfAvailable(DisabledUploadHistory::new),
                    metrics, keyPrefix, cleanup.getOrphanGracePeriod(), Clock.systemUTC());
        }
        return new ScheduledCleanup(releasedCleanup, sweep, trigger(cleanup));
    }

    private static CronTrigger trigger(Dedup4jProperties.Cleanup cleanup) {
        ZoneId zone;
        try {
            zone = cleanup.getZone() == null || cleanup.getZone().isBlank()
                    ? ZoneId.systemDefault()
                    : ZoneId.of(cleanup.getZone());
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid " + PREFIX + "zone '" + cleanup.getZone() + "': " + invalid.getMessage(), invalid);
        }
        try {
            return new CronTrigger(cleanup.getCron(), zone);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid " + PREFIX + "cron '" + cleanup.getCron() + "': " + invalid.getMessage(), invalid);
        }
    }
}
