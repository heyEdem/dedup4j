package com.edem.dedup4j.autoconfigure.service;

import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.key.ObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import com.edem.dedup4j.service.BlobDeduplicationService;
import com.edem.dedup4j.facade.Dedup4j;
import com.edem.dedup4j.facade.DefaultDedup4j;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class Dedup4jServiceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Dedup4jServiceAutoConfiguration.class))
            .withUserConfiguration(RequiredInfrastructure.class);

    @Test
    void registersDefaultsOnce() {
        runner.run(context -> {
            assertEquals(1, context.getBeansOfType(AssetContentRepository.class).size());
            assertEquals(1, context.getBeansOfType(AssetContentMutationService.class).size());
            assertEquals(1, context.getBeansOfType(ReferenceCountService.class).size());
            assertEquals(1, context.getBeansOfType(ContentHasher.class).size());
            assertEquals(1, context.getBeansOfType(ObjectKeyStrategy.class).size());
            assertEquals(1, context.getBeansOfType(Dedup4jMetrics.class).size());
            assertEquals(1, context.getBeansOfType(BlobDeduplicationService.class).size());
            assertEquals(1, context.getBeansOfType(Dedup4j.class).size());
            org.assertj.core.api.Assertions.assertThat(context.getBean(Dedup4j.class))
                    .isInstanceOf(DefaultDedup4j.class);
        });
    }

    @Test
    void backsOffForApplicationFacade() {
        FacadeOverride override = new FacadeOverride();
        runner.withUserConfiguration(FacadeOverride.class).run(context -> {
            assertSame(override.FACADE, context.getBean(Dedup4j.class));
            assertEquals(1, context.getBeansOfType(BlobDeduplicationService.class).size());
        });
    }

    @Test
    void backsOffWithoutPersistenceInfrastructure() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Dedup4jServiceAutoConfiguration.class))
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(BlobDeduplicationService.class)
                        .doesNotHaveBean(AssetContentRepository.class));
    }

    @Test
    void usesApplicationMeterRegistryWhenPresent() {
        runner.withUserConfiguration(MeterRegistryOverride.class).run(context -> {
            Dedup4jMetrics metrics = context.getBean(Dedup4jMetrics.class);
            metrics.recordUpload(7, false);
            assertEquals(1.0, context.getBean(MeterRegistry.class)
                    .get("dedup4j.uploads").counter().count());
        });
    }

    @Test
    void applicationCollaboratorsBackOffIndividually() {
        assertOverride(RepositoryOverride.class, AssetContentRepository.class, RepositoryOverride.REPOSITORY);
        assertOverride(MutationOverride.class, AssetContentMutationService.class, MutationOverride.MUTATION);
        assertOverride(ReferenceOverride.class, ReferenceCountService.class, ReferenceOverride.REFERENCES);
        assertOverride(HasherOverride.class, ContentHasher.class, HasherOverride.HASHER);
        assertOverride(KeyOverride.class, ObjectKeyStrategy.class, KeyOverride.KEYS);
        assertOverride(MetricsOverride.class, Dedup4jMetrics.class, MetricsOverride.METRICS);
        assertOverride(ServiceOverride.class, BlobDeduplicationService.class, ServiceOverride.SERVICE);
    }

    private <T> void assertOverride(Class<?> configuration, Class<T> type, T expected) {
        runner.withUserConfiguration(configuration).run(context -> {
            assertSame(expected, context.getBean(type));
            assertEquals(1, context.getBeansOfType(AssetContentRepository.class).size());
            assertEquals(1, context.getBeansOfType(AssetContentMutationService.class).size());
            assertEquals(1, context.getBeansOfType(ReferenceCountService.class).size());
            assertEquals(1, context.getBeansOfType(ContentHasher.class).size());
            assertEquals(1, context.getBeansOfType(ObjectKeyStrategy.class).size());
            assertEquals(1, context.getBeansOfType(Dedup4jMetrics.class).size());
            assertEquals(1, context.getBeansOfType(BlobDeduplicationService.class).size());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredInfrastructure {
        static final EntityManager ENTITY_MANAGER = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class}, (p, m, a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("toString")) return "test-entity-manager";
            return null;
        });
        static final BlobStorage STORAGE = new TestStorage();
        @Bean EntityManager entityManager() { return ENTITY_MANAGER; }
        @Bean PlatformTransactionManager transactionManager() { return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        }; }
        @Bean BlobStorage blobStorage() { return STORAGE; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RepositoryOverride {
        static final AssetContentRepository REPOSITORY = new AssetContentRepository(RequiredInfrastructure.ENTITY_MANAGER);
        @Bean AssetContentRepository repository() { return REPOSITORY; }
    }

    @Configuration(proxyBeanMethods = false)
    static class MutationOverride {
        static final AssetContentRepository REPOSITORY = new AssetContentRepository(RequiredInfrastructure.ENTITY_MANAGER);
        static final AssetContentMutationService MUTATION = new AssetContentMutationService(RequiredInfrastructure.ENTITY_MANAGER, REPOSITORY);
        @Bean AssetContentMutationService mutation() { return MUTATION; }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReferenceOverride {
        static final AssetContentRepository REPOSITORY = new AssetContentRepository(RequiredInfrastructure.ENTITY_MANAGER);
        static final ReferenceCountService REFERENCES = new ReferenceCountService(REPOSITORY, RequiredInfrastructure.STORAGE);
        @Bean ReferenceCountService references() { return REFERENCES; }
    }

    @Configuration(proxyBeanMethods = false)
    static class HasherOverride {
        static final ContentHasher HASHER = new com.edem.dedup4j.core.hash.Sha256ContentHasher();
        @Bean ContentHasher hasher() { return HASHER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class KeyOverride {
        static final ObjectKeyStrategy KEYS = new com.edem.dedup4j.core.key.HashObjectKeyStrategy("override");
        @Bean ObjectKeyStrategy keys() { return KEYS; }
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsOverride {
        static final Dedup4jMetrics METRICS = new Dedup4jMetrics(null);
        @Bean Dedup4jMetrics metrics() { return METRICS; }
    }

    @Configuration(proxyBeanMethods = false)
    static class ServiceOverride {
        static final BlobDeduplicationService SERVICE = new BlobDeduplicationService() {
            @Override public BlobReference store(StoreBlobCommand command) { return new BlobReference(UUID.randomUUID(), new ContentHash("sha-256", "a", 0), "text/plain", "test", "bucket", "a", false); }
            @Override public void retain(UUID id) { }
            @Override public void release(UUID id) { }
            @Override public BlobResource get(UUID id) { return null; }
            @Override public BlobLocation location(UUID id) { return new BlobLocation("test", "bucket", "a"); }
        };
        @Bean BlobDeduplicationService service() { return SERVICE; }
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryOverride {
        @Bean MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FacadeOverride {
        private static final Dedup4j FACADE = new TestFacade();

        @Bean Dedup4j dedup4j() { return FACADE; }
    }

    private static final class TestFacade implements Dedup4j {
        @Override public BlobReference store(org.springframework.web.multipart.MultipartFile file) { return null; }
        @Override public BlobReference store(java.nio.file.Path path) { return null; }
        @Override public BlobReference store(byte[] content, String filename, String contentType) { return null; }
        @Override public BlobReference store(java.io.InputStream content, long sizeBytes, String filename, String contentType, java.util.Map<String, String> metadata) { return null; }
        @Override public com.edem.dedup4j.facade.BatchStoreResult storeAll(org.springframework.web.multipart.MultipartFile[] files) { return null; }
    }

    static final class TestStorage implements BlobStorage {
        @Override public StoredBlob put(PutBlobRequest request) { return new StoredBlob(request.objectKey(), "test", "bucket", request.sizeBytes(), request.contentType(), null, Instant.now()); }
        @Override public BlobResource get(String key) { return null; }
        @Override public void delete(String key) { }
        @Override public boolean exists(String key) { return false; }
    }
}
