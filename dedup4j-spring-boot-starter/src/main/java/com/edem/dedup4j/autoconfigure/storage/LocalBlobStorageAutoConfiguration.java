package com.edem.dedup4j.autoconfigure.storage;

import com.edem.dedup4j.autoconfigure.Dedup4jAutoConfiguration;
import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = Dedup4jAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dedup4j.storage", name = "provider", havingValue = "local")
@ConditionalOnMissingBean(BlobStorage.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class LocalBlobStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    LocalBlobStorageProperties localBlobStorageProperties(Dedup4jProperties properties) {
        LocalBlobStorageProperties target = new LocalBlobStorageProperties();
        target.setRootDirectory(properties.getStorage().getLocal().getRootDirectory());
        return target;
    }

    @Bean(name = "localBlobStorage")
    @ConditionalOnMissingBean(BlobStorage.class)
    LocalBlobStorage localBlobStorage(LocalBlobStorageProperties properties) {
        return new LocalBlobStorage(properties);
    }
}
