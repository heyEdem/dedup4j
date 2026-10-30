package com.edem.dedup4j.autoconfigure.reconcile;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.autoconfigure.service.Dedup4jServiceAutoConfiguration;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import com.edem.dedup4j.reconcile.ContentReferenceCountSource;
import com.edem.dedup4j.reconcile.CoordinatedRepair;
import com.edem.dedup4j.reconcile.LogicalReferenceCountSource;
import com.edem.dedup4j.reconcile.ReconciliationMode;
import com.edem.dedup4j.reconcile.ReconciliationService;
import com.edem.dedup4j.reconcile.ScheduledReconciliation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Registers scheduled reconciliation when {@code dedup4j.reconciliation.enabled} is true: a
 * read-only report by default, or coordinated repair with {@code mode=repair}. Misconfiguration
 * fails startup rather than leaving drift silently unchecked or repairing on an unsupported setup.
 */
@AutoConfiguration(after = Dedup4jServiceAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "dedup4j.reconciliation.enabled")
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jReconciliationAutoConfiguration {

    private static final String PREFIX = "dedup4j.reconciliation.";
    private static final int PAGE_SIZE = 500;

    @Bean
    @ConditionalOnMissingBean(ScheduledReconciliation.class)
    ScheduledReconciliation scheduledReconciliation(
            Dedup4jProperties properties,
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<EntityManager> entityManagers,
            ObjectProvider<LogicalReferenceCountSource> sources,
            ObjectProvider<ContentReferenceCountSource> authorities,
            ObjectProvider<CoordinatedRepair> repairs,
            ObjectProvider<Dedup4jMetrics> metrics
    ) {
        Dedup4jProperties.Reconciliation reconciliation = properties.getReconciliation();
        LogicalReferenceCountSource source = sources.getIfUnique();
        if (source == null) {
            throw new IllegalStateException(PREFIX + "enabled=true requires one LogicalReferenceCountSource "
                    + "bean, or one marked @Primary, found " + sources.stream().count());
        }
        if (reconciliation.getMode() == ReconciliationMode.REPAIR) {
            return repairSchedule(reconciliation, source, authorities, repairs, metrics);
        }
        AssetContentRepository contentRepository = readRepository(entityManagerFactories, entityManagers);
        if (reconciliation.getSampleSize() < 0) {
            throw new IllegalArgumentException(PREFIX + "sample-size must not be negative");
        }
        return new ScheduledReconciliation(
                new ReconciliationService(contentRepository),
                source,
                trigger(reconciliation),
                PAGE_SIZE,
                reconciliation.getSampleSize(),
                metrics.getIfAvailable(() -> new Dedup4jMetrics(null)),
                Clock.systemUTC()
        );
    }

    /**
     * Uses the application's {@link CoordinatedRepair} bean, which runs every read and write in its
     * own transaction, so its EntityManager must be a transaction-bound shared one (as Spring Boot's is).
     */
    private static ScheduledReconciliation repairSchedule(
            Dedup4jProperties.Reconciliation reconciliation,
            LogicalReferenceCountSource source,
            ObjectProvider<ContentReferenceCountSource> authorities,
            ObjectProvider<CoordinatedRepair> repairs,
            ObjectProvider<Dedup4jMetrics> metrics
    ) {
        ContentReferenceCountSource authority = authorities.getIfUnique();
        if (authority == null) {
            throw new IllegalStateException(PREFIX + "mode=repair requires one ContentReferenceCountSource "
                    + "bean, or one marked @Primary, found " + authorities.stream().count());
        }
        CoordinatedRepair repair = repairs.getIfUnique();
        if (repair == null) {
            throw new IllegalStateException(PREFIX + "mode=repair requires a CoordinatedRepair bean");
        }
        repair.requireSupportedDatabase();
        return new ScheduledReconciliation(repair, source, authority, trigger(reconciliation),
                metrics.getIfAvailable(() -> new Dedup4jMetrics(null)), Clock.systemUTC());
    }

    /**
     * Scheduled runs read through their own shared EntityManager, which opens and closes a
     * persistence context per query. An application EntityManager, whether raw, extended or
     * application-managed, may keep every row it reads, returning stale counts on later runs, and is
     * not safe to share with the scheduler thread, so it is never reused: only its factory is.
     */
    private static AssetContentRepository readRepository(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<EntityManager> entityManagers
    ) {
        EntityManagerFactory factory = entityManagerFactories.getIfUnique();
        if (factory == null) {
            EntityManager entityManager = entityManagers.getIfUnique();
            factory = entityManager == null ? null : entityManager.getEntityManagerFactory();
        }
        if (factory == null) {
            throw new IllegalStateException(PREFIX + "enabled=true requires an EntityManagerFactory, so "
                    + "scheduled runs open their own persistence contexts");
        }
        return new AssetContentRepository(SharedEntityManagerCreator.createSharedEntityManager(factory));
    }

    private static CronTrigger trigger(Dedup4jProperties.Reconciliation reconciliation) {
        ZoneId zone;
        try {
            zone = reconciliation.getZone() == null || reconciliation.getZone().isBlank()
                    ? ZoneId.systemDefault()
                    : ZoneId.of(reconciliation.getZone());
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid " + PREFIX + "zone '" + reconciliation.getZone() + "': " + invalid.getMessage(), invalid);
        }
        try {
            return new CronTrigger(reconciliation.getCron(), zone);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid " + PREFIX + "cron '" + reconciliation.getCron() + "': " + invalid.getMessage(), invalid);
        }
    }
}
