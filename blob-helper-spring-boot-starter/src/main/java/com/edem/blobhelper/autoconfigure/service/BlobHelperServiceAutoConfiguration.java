package com.edem.blobhelper.autoconfigure.service;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration;
import com.edem.blobhelper.autoconfigure.storage.AzureBlobStorageAutoConfiguration;
import com.edem.blobhelper.autoconfigure.storage.LocalBlobStorageAutoConfiguration;
import com.edem.blobhelper.autoconfigure.storage.S3BlobStorageAutoConfiguration;
import com.edem.blobhelper.core.hash.ContentHasher;
import com.edem.blobhelper.core.hash.Sha256ContentHasher;
import com.edem.blobhelper.core.key.HashObjectKeyStrategy;
import com.edem.blobhelper.core.key.ObjectKeyStrategy;
import com.edem.blobhelper.core.storage.BlobStorage;
import com.edem.blobhelper.jpa.AssetContentMutationService;
import com.edem.blobhelper.jpa.AssetContentRepository;
import com.edem.blobhelper.jpa.ReferenceCountService;
import com.edem.blobhelper.observability.BlobHelperMetrics;
import com.edem.blobhelper.service.BlobDeduplicationService;
import com.edem.blobhelper.service.DefaultBlobDeduplicationService;
import com.edem.blobhelper.service.SpringTransactionalBlobDeduplicationService;
import com.edem.blobhelper.facade.BlobHelper;
import com.edem.blobhelper.facade.DefaultBlobHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@AutoConfigureAfter(
        value = {
                HibernateJpaAutoConfiguration.class,
                DataJpaRepositoriesAutoConfiguration.class,
                LocalBlobStorageAutoConfiguration.class,
                S3BlobStorageAutoConfiguration.class,
                AzureBlobStorageAutoConfiguration.class
        },
        name = "com.edem.blobhelper.autoconfigure.persistence.BlobHelperPersistenceAutoConfiguration"
)
@Conditional(BlobHelperServiceAutoConfiguration.PersistenceInfrastructureCondition.class)
@EnableConfigurationProperties(BlobHelperProperties.class)
public class BlobHelperServiceAutoConfiguration {

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
    ObjectKeyStrategy objectKeyStrategy(BlobHelperProperties properties) {
        return new HashObjectKeyStrategy(properties.getStorage().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(BlobHelperMetrics.class)
    BlobHelperMetrics blobHelperMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new BlobHelperMetrics(meterRegistryProvider.getIfAvailable());
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
            BlobHelperMetrics metrics,
            PlatformTransactionManager transactionManager
    ) {
        BlobDeduplicationService delegate = new DefaultBlobDeduplicationService(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                metrics
        );
        return new SpringTransactionalBlobDeduplicationService(
                delegate, repository, referenceCountService, transactionManager
        );
    }

    @Bean
    @ConditionalOnMissingBean(BlobHelper.class)
    BlobHelper blobHelper(BlobDeduplicationService service, BlobHelperProperties properties) {
        return new DefaultBlobHelper(service, properties);
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
