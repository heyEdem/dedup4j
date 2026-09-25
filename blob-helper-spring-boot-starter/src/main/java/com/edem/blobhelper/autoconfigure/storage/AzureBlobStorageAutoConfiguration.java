package com.edem.blobhelper.autoconfigure.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration;
import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnClass(BlobContainerClient.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "azure")
@ConditionalOnMissingBean(BlobStorage.class)
@EnableConfigurationProperties(BlobHelperProperties.class)
public class AzureBlobStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AzureBlobStorageProperties azureBlobStorageProperties(BlobHelperProperties properties) {
        BlobHelperProperties.Azure source = properties.getStorage().getAzure();
        if (source.getContainer() == null || source.getContainer().isBlank()) {
            throw new IllegalStateException("blob-helper.storage.azure.container is required when provider=azure");
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
    BlobContainerClient blobHelperAzureContainerClient(AzureBlobStorageProperties properties) {
        return AzureBlobStorage.createClient(properties);
    }

    @Bean(name = "azureBlobStorage")
    @ConditionalOnMissingBean(BlobStorage.class)
    AzureBlobStorage azureBlobStorage(BlobContainerClient client, AzureBlobStorageProperties properties) {
        return new AzureBlobStorage(client, properties);
    }
}
