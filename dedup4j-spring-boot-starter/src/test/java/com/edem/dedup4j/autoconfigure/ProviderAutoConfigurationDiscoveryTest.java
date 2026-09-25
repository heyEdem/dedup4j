package com.edem.dedup4j.autoconfigure;

import com.azure.storage.blob.BlobContainerClient;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.s3.S3BlobStorage;
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
        runner.withPropertyValues("dedup4j.storage.provider=local")
                .run(context -> assertThat(context)
                        .hasSingleBean(Dedup4jProperties.class)
                        .hasSingleBean(Dedup4jAutoConfiguration.BlobStorageProviderValidator.class)
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
                        "dedup4j.storage.provider=s3",
                        "dedup4j.storage.s3.bucket=media",
                        "dedup4j.storage.s3.region=us-east-1")
                .run(context -> assertThat(context)
                        .hasSingleBean(Dedup4jProperties.class)
                        .hasSingleBean(Dedup4jAutoConfiguration.BlobStorageProviderValidator.class)
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
                        "dedup4j.storage.provider=azure",
                        "dedup4j.storage.azure.container=media",
                        "dedup4j.storage.azure.connection-string=UseDevelopmentStorage=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(Dedup4jProperties.class)
                        .hasSingleBean(Dedup4jAutoConfiguration.BlobStorageProviderValidator.class)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(AzureBlobStorage.class)
                        .hasSingleBean(BlobContainerClient.class)
                        .doesNotHaveBean(LocalBlobStorage.class)
                        .doesNotHaveBean(S3BlobStorage.class)
                        .doesNotHaveBean(S3Client.class));
    }

    @Test
    void acceptsCaseInsensitiveProviderSelectionDuringDiscovery() {
        runner.withPropertyValues("dedup4j.storage.provider=LOCAL")
                .run(context -> assertThat(context)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(LocalBlobStorage.class));
    }

    @Test
    void rejectsWhitespacePaddedProviderSelectionDuringDiscovery() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("padded-provider", Map.of(
                                "dedup4j.storage.provider", " local "))))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("Invalid or missing 'dedup4j.storage.provider'"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MinimalConsumer {
    }
}
