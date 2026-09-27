package com.edem.dedup4j.autoconfigure.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.edem.dedup4j.autoconfigure.Dedup4jAutoConfiguration;
import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = Dedup4jAutoConfiguration.class)
@ConditionalOnClass(BlobContainerClient.class)
@ConditionalOnProperty(prefix = "dedup4j.storage", name = "provider", havingValue = "azure")
@ConditionalOnMissingBean(BlobStorage.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class AzureBlobStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AzureBlobStorageProperties azureBlobStorageProperties(Dedup4jProperties properties) {
        Dedup4jProperties.Azure source = properties.getStorage().getAzure();
        if (source.getContainer() == null || source.getContainer().isBlank()) {
            throw new IllegalStateException("dedup4j.storage.azure.container is required when provider=azure");
        }
        AzureBlobStorageProperties target = new AzureBlobStorageProperties();
        target.setContainer(source.getContainer());
        target.setConnectionString(source.getConnectionString());
        target.setEndpoint(source.getEndpoint());
        target.setAccountName(source.getAccountName());
        return target;
    }

    @Bean
    @ConditionalOnMissingBean(BlobContainerClient.class)
    BlobContainerClient dedup4jAzureContainerClient(AzureBlobStorageProperties properties) {
        return AzureBlobStorage.createClient(properties);
    }

    @Bean(name = "azureBlobStorage")
    @ConditionalOnMissingBean(BlobStorage.class)
    AzureBlobStorage azureBlobStorage(BlobContainerClient client, AzureBlobStorageProperties properties) {
        return new AzureBlobStorage(client, properties);
    }
}
