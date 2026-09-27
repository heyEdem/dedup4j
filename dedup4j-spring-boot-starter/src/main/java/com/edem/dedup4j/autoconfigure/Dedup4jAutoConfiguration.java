package com.edem.dedup4j.autoconfigure;

import com.edem.dedup4j.autoconfigure.persistence.Dedup4jPersistenceAutoConfiguration;
import com.edem.dedup4j.autoconfigure.service.Dedup4jServiceAutoConfiguration;
import com.edem.dedup4j.core.storage.BlobStorage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Set;
import java.util.Locale;

/**
 * Auto-configuration that validates the dedup4j storage wiring.
 *
 * <p>Provider-specific starter auto-configurations create the selected
 * {@link BlobStorage}; this configuration validates that exactly one provider
 * is selected at startup.</p>
 */
@AutoConfiguration(after = {
        Dedup4jPersistenceAutoConfiguration.class,
        Dedup4jServiceAutoConfiguration.class
})
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jAutoConfiguration {

    @Bean
    public static BlobStorageProviderValidator blobStorageProviderValidator(
            ConfigurableListableBeanFactory beanFactory,
            Dedup4jProperties properties
    ) {
        return new BlobStorageProviderValidator(beanFactory, properties);
    }

    static final class BlobStorageProviderValidator implements SmartInitializingSingleton {

        static final Set<String> SUPPORTED_PROVIDERS = Set.of("local", "s3", "azure");

        private final ConfigurableListableBeanFactory beanFactory;
        private final String provider;

        BlobStorageProviderValidator(
                ConfigurableListableBeanFactory beanFactory,
                Dedup4jProperties properties
        ) {
            this.beanFactory = beanFactory;
            String configured = properties.getStorage().getProvider();
            this.provider = configured == null ? null : configured.toLowerCase(Locale.ROOT);
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (provider == null || provider.isBlank() || !SUPPORTED_PROVIDERS.contains(provider)) {
                throw new IllegalStateException(
                        "Invalid or missing 'dedup4j.storage.provider' value '" + provider
                                + "'. Supported providers: " + String.join(", ", SUPPORTED_PROVIDERS) + "."
                );
            }

            String[] candidateNames = beanFactory.getBeanNamesForType(BlobStorage.class);
            if (candidateNames.length == 0) {
                throw new IllegalStateException(
                        "No BlobStorage provider is configured for 'dedup4j.storage.provider'."
                );
            }
            if (candidateNames.length > 1) {
                throw ambiguous(candidateNames);
            }
        }

        private static IllegalStateException ambiguous(String[] names) {
            return new IllegalStateException(
                    "Ambiguous BlobStorage configuration: multiple providers found ("
                            + String.join(", ", names)
                            + "). Remove extra BlobStorage definitions; 'dedup4j.storage.provider' cannot resolve ambiguity."
            );
        }

    }
}
