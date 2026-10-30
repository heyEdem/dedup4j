package com.edem.dedup4j.autoconfigure.cleanup;

import com.edem.dedup4j.autoconfigure.service.Dedup4jServiceAutoConfiguration;
import com.edem.dedup4j.cleanup.ScheduledCleanup;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.core.storage.ListedBlob;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jCleanupAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Dedup4jServiceAutoConfiguration.class, Dedup4jCleanupAutoConfiguration.class))
            .withUserConfiguration(Infrastructure.class);

    @Test
    void schedulesCleanupByDefault() {
        runner.withUserConfiguration(PlainStorage.class).run(context -> {
            ScheduledCleanup cleanup = context.getBean(ScheduledCleanup.class);
            assertThat(cleanup.isRunning()).isTrue();
            assertThat(cleanup.cronExpression()).isEqualTo("0 30 3 * * *");
            assertThat(cleanup.sweepsOrphans()).isFalse();
        });
    }

    @Test
    void sweepsOrphansOnlyWhenEnabled() {
        runner.withUserConfiguration(ListingStorage.class)
                .withPropertyValues("dedup4j.storage.key-prefix=media", "dedup4j.cleanup.cron=0 0 1 * * *",
                        "dedup4j.cleanup.zone=UTC", "dedup4j.cleanup.orphans.enabled=true",
                        "dedup4j.cleanup.orphans.grace-period=2h")
                .run(context -> {
                    ScheduledCleanup cleanup = context.getBean(ScheduledCleanup.class);
                    assertThat(cleanup.sweepsOrphans()).isTrue();
                    assertThat(cleanup.cronExpression()).isEqualTo("0 0 1 * * *");
                });
    }

    @Test
    void leavesTheOrphanSweepOffByDefault() {
        runner.withUserConfiguration(ListingStorage.class)
                .withPropertyValues("dedup4j.storage.key-prefix=media")
                .run(context -> assertThat(context.getBean(ScheduledCleanup.class).sweepsOrphans()).isFalse());
    }

    @Test
    void enabledOrphanSweepWithoutAUsablePrefixFailsStartup() {
        runner.withUserConfiguration(ListingStorage.class)
                .withPropertyValues("dedup4j.cleanup.orphans.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("orphans.enabled=true requires dedup4j.storage.key-prefix"));
        runner.withUserConfiguration(ListingStorage.class)
                .withPropertyValues("dedup4j.cleanup.orphans.enabled=true", "dedup4j.storage.key-prefix=media/")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("found 'media/'"));
    }

    @Test
    void enabledOrphanSweepOnStorageThatCannotListFailsStartup() {
        runner.withUserConfiguration(PlainStorage.class)
                .withPropertyValues("dedup4j.cleanup.orphans.enabled=true", "dedup4j.storage.key-prefix=media")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("ListableBlobStorage"));
    }

    @Test
    void canBeDisabled() {
        runner.withUserConfiguration(ListingStorage.class)
                .withPropertyValues("dedup4j.cleanup.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledCleanup.class));
    }

    @Test
    void invalidCronFailsStartup() {
        runner.withUserConfiguration(PlainStorage.class)
                .withPropertyValues("dedup4j.cleanup.cron=nightly")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("Invalid dedup4j.cleanup.cron 'nightly'"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean EntityManagerFactory entityManagerFactory() {
            return stub(EntityManagerFactory.class);
        }

        @Bean EntityManager entityManager() {
            return stub(EntityManager.class);
        }

        @Bean PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }

        private static <T> T stub(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "stub " + type.getSimpleName();
                        default -> null;
                    }));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainStorage {
        @Bean BlobStorage blobStorage() {
            return new BlobStorage() {
                @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
                @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public void delete(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public boolean exists(String objectKey) { return false; }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListingStorage {
        @Bean BlobStorage blobStorage() {
            return new ListableBlobStorage() {
                @Override public StoredBlob put(PutBlobRequest request) { throw new UnsupportedOperationException(); }
                @Override public BlobResource get(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public void delete(String objectKey) { throw new UnsupportedOperationException(); }
                @Override public boolean exists(String objectKey) { return false; }
                @Override public Stream<ListedBlob> list(String keyPrefix) { return Stream.empty(); }
            };
        }
    }
}
