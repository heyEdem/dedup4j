package com.edem.blobhelper.autoconfigure;

import com.azure.storage.blob.BlobContainerClient;
import com.edem.blobhelper.core.storage.BlobStorage;
import com.edem.blobhelper.storage.azure.AzureBlobStorage;
import com.edem.blobhelper.storage.local.LocalBlobStorage;
import com.edem.blobhelper.storage.s3.S3BlobStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

class ProviderAutoConfigurationDiscoveryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MinimalConsumer.class);

    @Test
    void discoversLocalProviderConfigurationFromImports() {
        runner.withPropertyValues("blob-helper.storage.provider=local")
                .run(context -> assertThat(context)
                        .hasSingleBean(BlobHelperProperties.class)
                        .hasSingleBean(BlobHelperAutoConfiguration.BlobStorageProviderValidator.class)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(LocalBlobStorage.class)
                        .doesNotHaveBean(S3BlobStorage.class)
                        .doesNotHaveBean(S3Client.class)
                        .doesNotHaveBean(AzureBlobStorage.class)
                        .doesNotHaveBean(BlobContainerClient.class));
    }

    @Test
    void discoversS3ProviderConfigurationFromImports() {
        runner.withPropertyValues(
                        "blob-helper.storage.provider=s3",
                        "blob-helper.storage.s3.bucket=media",
                        "blob-helper.storage.s3.region=us-east-1")
                .run(context -> assertThat(context)
                        .hasSingleBean(BlobHelperProperties.class)
                        .hasSingleBean(BlobHelperAutoConfiguration.BlobStorageProviderValidator.class)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(S3BlobStorage.class)
                        .hasSingleBean(S3Client.class)
                        .doesNotHaveBean(LocalBlobStorage.class)
                        .doesNotHaveBean(AzureBlobStorage.class)
                        .doesNotHaveBean(BlobContainerClient.class));
    }

    @Test
    void discoversAzureProviderConfigurationFromImports() {
        runner.withPropertyValues(
                        "blob-helper.storage.provider=azure",
                        "blob-helper.storage.azure.container=media",
                        "blob-helper.storage.azure.connection-string=UseDevelopmentStorage=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(BlobHelperProperties.class)
                        .hasSingleBean(BlobHelperAutoConfiguration.BlobStorageProviderValidator.class)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(AzureBlobStorage.class)
                        .hasSingleBean(BlobContainerClient.class)
                        .doesNotHaveBean(LocalBlobStorage.class)
                        .doesNotHaveBean(S3BlobStorage.class)
                        .doesNotHaveBean(S3Client.class));
    }

    @Test
    void acceptsCaseInsensitiveProviderSelectionDuringDiscovery() {
        runner.withPropertyValues("blob-helper.storage.provider=LOCAL")
                .run(context -> assertThat(context)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(LocalBlobStorage.class));
    }

    @Test
    void rejectsWhitespacePaddedProviderSelectionDuringDiscovery() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("padded-provider", Map.of(
                                "blob-helper.storage.provider", " local "))))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("Invalid or missing 'blob-helper.storage.provider'"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MinimalConsumer {
    }
}
