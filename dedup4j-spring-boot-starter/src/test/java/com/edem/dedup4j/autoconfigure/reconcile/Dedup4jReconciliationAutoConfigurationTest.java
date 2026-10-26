package com.edem.dedup4j.autoconfigure.reconcile;

import com.edem.dedup4j.autoconfigure.service.Dedup4jServiceAutoConfiguration;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.reconcile.LogicalReferenceCountSource;
import com.edem.dedup4j.reconcile.ScheduledReconciliation;
import com.edem.dedup4j.jpa.AssetContent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jReconciliationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Dedup4jServiceAutoConfiguration.class, Dedup4jReconciliationAutoConfiguration.class))
            .withUserConfiguration(Infrastructure.class);

    @Test
    void disabledByDefault() {
        runner.withUserConfiguration(OneSource.class)
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledReconciliation.class));
    }

    @Test
    void enabledWithASourceStartsTheSchedule() {
        runner.withUserConfiguration(OneSource.class)
                .withPropertyValues(
                        "dedup4j.reconciliation.enabled=true",
                        "dedup4j.reconciliation.cron=0 15 4 * * *",
                        "dedup4j.reconciliation.zone=UTC")
                .run(context -> {
                    ScheduledReconciliation scheduled = context.getBean(ScheduledReconciliation.class);
                    assertThat(scheduled.isRunning()).isTrue();
                    assertThat(scheduled.cronExpression()).isEqualTo("0 15 4 * * *");
                    assertThat(scheduled.lastRun()).isEmpty();
                });
    }

    @Test
    void enabledWithoutASourceFailsStartup() {
        runner.withPropertyValues("dedup4j.reconciliation.enabled=true")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessage(
                        "dedup4j.reconciliation.enabled=true requires one LogicalReferenceCountSource bean, or one marked @Primary, found 0"));
    }

    @Test
    void enabledWithTwoSourcesFailsStartup() {
        runner.withUserConfiguration(OneSource.class, SecondSource.class)
                .withPropertyValues("dedup4j.reconciliation.enabled=true")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessage(
                        "dedup4j.reconciliation.enabled=true requires one LogicalReferenceCountSource bean, or one marked @Primary, found 2"));
    }

    @Test
    void aPrimarySourceIsUsedWhenThereAreSeveral() {
        runner.withBean("secondarySource", LogicalReferenceCountSource.class, () -> () -> {
                    throw new IllegalStateException("secondary");
                })
                .withBean("primarySource", LogicalReferenceCountSource.class, () -> () -> {
                    throw new IllegalStateException("primary");
                }, definition -> definition.setPrimary(true))
                .withPropertyValues("dedup4j.reconciliation.enabled=true")
                .run(context -> assertThat(context.getBean(ScheduledReconciliation.class).runNow().failureMessage())
                        .isEqualTo("primary"));
    }

    @Test
    void invalidCronFailsStartup() {
        runner.withUserConfiguration(OneSource.class)
                .withPropertyValues("dedup4j.reconciliation.enabled=true", "dedup4j.reconciliation.cron=nightly")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Invalid dedup4j.reconciliation.cron 'nightly'"));
    }

    @Test
    void invalidZoneFailsStartup() {
        runner.withUserConfiguration(OneSource.class)
                .withPropertyValues("dedup4j.reconciliation.enabled=true", "dedup4j.reconciliation.zone=Mars/Base")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Invalid dedup4j.reconciliation.zone 'Mars/Base'"));
    }

    @Test
    void negativeSampleSizeFailsStartup() {
        runner.withUserConfiguration(OneSource.class)
                .withPropertyValues("dedup4j.reconciliation.enabled=true", "dedup4j.reconciliation.sample-size=-1")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessage("dedup4j.reconciliation.sample-size must not be negative"));
    }

    @Test
    void repairModeIsRejected() {
        runner.withUserConfiguration(OneSource.class)
                .withPropertyValues("dedup4j.reconciliation.enabled=true", "dedup4j.reconciliation.mode=repair")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("dedup4j.reconciliation.mode"));
    }

    @Test
    void scheduledRunsReadFreshCountsEvenWithAnApplicationEntityManager() {
        assertFreshCountsAcrossRuns(true);
    }

    @Test
    void scheduledRunsReadFreshCountsWithOnlyAnApplicationEntityManager() {
        assertFreshCountsAcrossRuns(false);
    }

    @Test
    void anEntityManagerWithoutAFactoryFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        Dedup4jServiceAutoConfiguration.class, Dedup4jReconciliationAutoConfiguration.class))
                .withUserConfiguration(TransactionsAndStorage.class, OneSource.class)
                .withBean("entityManager", EntityManager.class, Infrastructure::stubEntityManager)
                .withPropertyValues("dedup4j.reconciliation.enabled=true")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessage(
                        "dedup4j.reconciliation.enabled=true requires an EntityManagerFactory, so scheduled "
                                + "runs open their own persistence contexts"));
    }

    /**
     * An application-managed EntityManager keeps every row it reads; scheduled runs must not reuse it, or a
     * count changed after the first run is never seen.
     */
    private static void assertFreshCountsAcrossRuns(boolean factoryBean) {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
        try {
            EntityManager setup = factory.createEntityManager();
            AssetContent content = new AssetContent(
                    "SHA-256", "c".repeat(64), 42L, "uploads/c", "local", "test-bucket", null, "bin");
            setup.getTransaction().begin();
            setup.createQuery("delete from AssetContent").executeUpdate();
            setup.persist(content);
            setup.getTransaction().commit();
            UUID id = content.getId();

            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            Dedup4jServiceAutoConfiguration.class, Dedup4jReconciliationAutoConfiguration.class))
                    .withUserConfiguration(TransactionsAndStorage.class)
                    .withBean("entityManager", EntityManager.class, factory::createEntityManager)
                    .withBean(LogicalReferenceCountSource.class, () -> () -> Map.of(id, 1L))
                    .withPropertyValues("dedup4j.reconciliation.enabled=true");
            if (factoryBean) {
                runner = runner.withBean(EntityManagerFactory.class, () -> factory);
            }
            runner.run(context -> {
                ScheduledReconciliation scheduled = context.getBean(ScheduledReconciliation.class);
                assertThat(scheduled.runNow().summary().mismatchCount()).isZero();

                setup.getTransaction().begin();
                setup.createQuery("update AssetContent c set c.refCount = 3 where c.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                setup.getTransaction().commit();

                assertThat(scheduled.runNow().summary().mismatchCount()).isEqualTo(1);
            });
            setup.close();
        } finally {
            factory.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneSource {
        @Bean LogicalReferenceCountSource logicalReferenceCountSource() { return Map::of; }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondSource {
        @Bean LogicalReferenceCountSource secondLogicalReferenceCountSource() { return Map::of; }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TransactionsAndStorage.class)
    static class Infrastructure {
        @Bean EntityManagerFactory entityManagerFactory() {
            return (EntityManagerFactory) Proxy.newProxyInstance(EntityManagerFactory.class.getClassLoader(),
                    new Class<?>[]{EntityManagerFactory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "test-entity-manager-factory";
                        default -> null;
                    });
        }

        @Bean EntityManager entityManager() {
            return stubEntityManager();
        }

        static EntityManager stubEntityManager() {
            return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                    new Class<?>[]{EntityManager.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "test-entity-manager";
                        default -> null;
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionsAndStorage {
        @Bean PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }

        @Bean BlobStorage blobStorage() {
            return new BlobStorage() {
                @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
                @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public void delete(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public boolean exists(String objectKey) { return false; }
            };
        }
    }
}
