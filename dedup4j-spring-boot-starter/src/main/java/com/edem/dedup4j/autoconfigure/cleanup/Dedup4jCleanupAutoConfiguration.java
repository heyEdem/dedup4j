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
 * joins the schedule with {@code dedup4j.cleanup.orphans.enabled=true}; it then needs storage that can
 * list its objects and a {@code dedup4j.storage.key-prefix} without a trailing slash, or startup fails.
 */
@AutoConfiguration(after = Dedup4jServiceAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "dedup4j.cleanup.enabled", matchIfMissing = true)
@ConditionalOnBean(ReleasedObjectCleanup.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jCleanupAutoConfiguration {

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
        OrphanedObjectSweep sweep = null;
        if (cleanup.getOrphans().isEnabled()) {
            String keyPrefix = properties.getStorage().getKeyPrefix();
            if (!(storage instanceof ListableBlobStorage listable)) {
                throw new IllegalStateException(PREFIX + "orphans.enabled=true requires storage that can list its "
                        + "objects (ListableBlobStorage); " + storage.getClass().getName() + " cannot");
            }
            if (keyPrefix == null || keyPrefix.isBlank() || keyPrefix.endsWith("/")) {
                throw new IllegalStateException(PREFIX + "orphans.enabled=true requires dedup4j.storage.key-prefix "
                        + "without a trailing slash, belonging to this database alone; found '" + keyPrefix + "'");
            }
            sweep = new OrphanedObjectSweep(listable, hasher, keyStrategy, repository, mutationService,
                    referenceCountService, transactionManager, history.getIfAvailable(DisabledUploadHistory::new),
                    metrics, keyPrefix, cleanup.getOrphans().getGracePeriod(), Clock.systemUTC());
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
