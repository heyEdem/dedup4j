package com.edem.dedup4j.autoconfigure.service;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.autoconfigure.Dedup4jAutoConfiguration;
import com.edem.dedup4j.autoconfigure.storage.AzureBlobStorageAutoConfiguration;
import com.edem.dedup4j.autoconfigure.storage.LocalBlobStorageAutoConfiguration;
import com.edem.dedup4j.autoconfigure.storage.S3BlobStorageAutoConfiguration;
import com.edem.dedup4j.cleanup.ReleasedObjectCleanup;
import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.key.ObjectKeyStrategy;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.history.DefaultInstanceId;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.jpa.ReferenceRepairRepository;
import com.edem.dedup4j.jpa.ReferenceRepairService;
import com.edem.dedup4j.reconcile.CoordinatedRepair;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import com.edem.dedup4j.service.BlobDeduplicationService;
import com.edem.dedup4j.service.ContentTypePolicy;
import com.edem.dedup4j.service.DefaultBlobDeduplicationService;
import com.edem.dedup4j.service.SpringTransactionalBlobDeduplicationService;
import com.edem.dedup4j.facade.BlobStore;
import com.edem.dedup4j.facade.DefaultBlobStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;

@AutoConfiguration(before = Dedup4jAutoConfiguration.class)
@AutoConfigureAfter(
        value = {
                HibernateJpaAutoConfiguration.class,
                DataJpaRepositoriesAutoConfiguration.class,
                LocalBlobStorageAutoConfiguration.class,
                S3BlobStorageAutoConfiguration.class,
                AzureBlobStorageAutoConfiguration.class
        },
        name = "com.edem.dedup4j.autoconfigure.persistence.Dedup4jPersistenceAutoConfiguration"
)
@Conditional(Dedup4jServiceAutoConfiguration.PersistenceInfrastructureCondition.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jServiceAutoConfiguration {

    private static final int CLEANUP_PAGE_SIZE = 500;
    private static final int REPAIR_PAGE_SIZE = 500;

    static final class PersistenceInfrastructureCondition extends AnyNestedCondition {

        PersistenceInfrastructureCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean({EntityManagerFactory.class, BlobStorage.class})
        static class BootJpaInfrastructure {
        }

        @ConditionalOnBean({EntityManager.class, PlatformTransactionManager.class, BlobStorage.class})
        static class ExplicitInfrastructure {
        }
    }

    @Bean
    @ConditionalOnMissingBean(AssetContentRepository.class)
    AssetContentRepository assetContentRepository(
            ObjectProvider<EntityManager> entityManagers,
            ObjectProvider<EntityManagerFactory> entityManagerFactories
    ) {
        return new AssetContentRepository(resolveEntityManager(entityManagers, entityManagerFactories));
    }

    @Bean
    @ConditionalOnMissingBean(AssetContentMutationService.class)
    AssetContentMutationService assetContentMutationService(
            ObjectProvider<EntityManager> entityManagers,
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            AssetContentRepository repository
    ) {
        return new AssetContentMutationService(
                resolveEntityManager(entityManagers, entityManagerFactories), repository
        );
    }

    @Bean
    @ConditionalOnMissingBean(ReferenceCountService.class)
    ReferenceCountService referenceCountService(
            AssetContentRepository repository,
            BlobStorage blobStorage
    ) {
        return new ReferenceCountService(repository, blobStorage);
    }

    @Bean
    @ConditionalOnMissingBean(ContentHasher.class)
    ContentHasher contentHasher() {
        return new Sha256ContentHasher();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectKeyStrategy.class)
    ObjectKeyStrategy objectKeyStrategy(Dedup4jProperties properties) {
        return new HashObjectKeyStrategy(properties.getStorage().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(Dedup4jMetrics.class)
    Dedup4jMetrics dedup4jMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new Dedup4jMetrics(meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(BlobDeduplicationService.class)
    BlobDeduplicationService blobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy,
            Dedup4jMetrics metrics,
            ObjectProvider<ContentTypePolicy> contentTypePolicy,
            PlatformTransactionManager transactionManager,
            ObjectProvider<UploadHistory> history,
            Dedup4jProperties properties
    ) {
        BlobDeduplicationService delegate = new DefaultBlobDeduplicationService(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                metrics,
                properties.getDeduplication().getMaxUploadSize().toBytes(),
                contentTypePolicy.getIfAvailable(() -> ContentTypePolicy.of(
                        properties.getDeduplication().getAllowedContentTypes()))
        );
        return new SpringTransactionalBlobDeduplicationService(
                delegate,
                repository,
                referenceCountService,
                transactionManager,
                history.getIfAvailable(DisabledUploadHistory::new),
                metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean(ReleasedObjectCleanup.class)
    ReleasedObjectCleanup releasedObjectCleanup(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            ObjectProvider<UploadHistory> history,
            Dedup4jMetrics metrics
    ) {
        return new ReleasedObjectCleanup(
                repository,
                referenceCountService,
                transactionManager,
                history.getIfAvailable(DisabledUploadHistory::new),
                metrics,
                CLEANUP_PAGE_SIZE
        );
    }

    @Bean
    @ConditionalOnMissingBean(ReferenceRepairService.class)
    ReferenceRepairService referenceRepairService(
            ObjectProvider<EntityManager> entityManagers,
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            AssetContentRepository repository
    ) {
        return new ReferenceRepairService(repository, new ReferenceRepairRepository(
                resolveEntityManager(entityManagers, entityManagerFactories)));
    }

    @Bean
    @ConditionalOnMissingBean(CoordinatedRepair.class)
    CoordinatedRepair coordinatedRepair(
            AssetContentRepository repository,
            ReferenceRepairService referenceRepairService,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            ObjectProvider<UploadHistory> history,
            Dedup4jMetrics metrics,
            @Value("${dedup4j.history.instance-id:${dedup4j.management.instance-id:}}") String instanceId
    ) {
        return new CoordinatedRepair(
                repository,
                referenceRepairService,
                referenceCountService,
                transactionManager,
                history.getIfAvailable(DisabledUploadHistory::new),
                metrics,
                instanceId == null || instanceId.isBlank() ? DefaultInstanceId.value() : instanceId,
                Clock.systemUTC(),
                REPAIR_PAGE_SIZE
        );
    }

    @Bean
    @ConditionalOnMissingBean(BlobStore.class)
    BlobStore blobStore(BlobDeduplicationService service) {
        return new DefaultBlobStore(service);
    }

    private static EntityManager resolveEntityManager(
            ObjectProvider<EntityManager> entityManagers,
            ObjectProvider<EntityManagerFactory> entityManagerFactories
    ) {
        EntityManager entityManager = entityManagers.getIfAvailable();
        if (entityManager != null) {
            return entityManager;
        }
        return SharedEntityManagerCreator.createSharedEntityManager(
                entityManagerFactories.getObject()
        );
    }
}
