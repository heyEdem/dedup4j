package com.edem.blobhelper.autoconfigure.storage;

import com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration;
import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.blobhelper.storage.local.LocalBlobStorage;
import com.edem.blobhelper.storage.local.LocalBlobStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "local")
@ConditionalOnMissingBean(BlobStorage.class)
@EnableConfigurationProperties(BlobHelperProperties.class)
public class LocalBlobStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    LocalBlobStorageProperties localBlobStorageProperties(BlobHelperProperties properties) {
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
