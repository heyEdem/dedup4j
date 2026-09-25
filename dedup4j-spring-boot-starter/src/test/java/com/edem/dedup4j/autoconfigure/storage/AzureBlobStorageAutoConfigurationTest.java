package com.edem.dedup4j.autoconfigure.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.azure.AzureBlobStorageProperties;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AzureBlobStorageAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AzureBlobStorageAutoConfiguration.class));

    @Test
    void createsDevelopmentClientWithoutNetwork() {
        runner.withPropertyValues("dedup4j.storage.provider=azure",
                        "dedup4j.storage.azure.container=media",
                        "dedup4j.storage.azure.connection-string=UseDevelopmentStorage=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AzureBlobStorage.class)
                            .hasSingleBean(BlobContainerClient.class);
                    assertThat(context.getBean(BlobContainerClient.class).getBlobContainerUrl())
                            .isEqualTo("http://127.0.0.1:10000/devstoreaccount1/media");
                });
    }

    @Test
    void reusesApplicationClient() {
        BlobContainerClient client = new BlobContainerClientBuilder().connectionString("UseDevelopmentStorage=true")
                .containerName("media").buildClient();
        runner.withBean(BlobContainerClient.class, () -> client)
                .withPropertyValues("dedup4j.storage.provider=azure", "dedup4j.storage.azure.container=media")
                .run(context -> {
                    assertThat(context).hasSingleBean(AzureBlobStorage.class).hasSingleBean(BlobStorage.class);
                    assertThat(context.getBean(BlobContainerClient.class)).isSameAs(client);
                    assertThat(ReflectionTestUtils.getField(context.getBean(AzureBlobStorage.class), "client"))
                            .isSameAs(client);
                });
    }

    @Test
    void reusesApplicationPropertiesBean() {
        AzureBlobStorageProperties properties = new AzureBlobStorageProperties();
        properties.setContainer("application-bucket");
        properties.setConnectionString("UseDevelopmentStorage=true");

        runner.withBean(AzureBlobStorageProperties.class, () -> properties)
                .withPropertyValues("dedup4j.storage.provider=azure")
                .run(context -> {
                    assertThat(context).hasSingleBean(AzureBlobStorage.class)
                            .hasSingleBean(BlobContainerClient.class);
                    assertThat(context.getBean(AzureBlobStorageProperties.class)).isSameAs(properties);
                    assertThat(context.getBean(BlobContainerClient.class).getBlobContainerUrl())
                            .isEqualTo("http://127.0.0.1:10000/devstoreaccount1/application-bucket");
                });
    }

    @Test
    void requiresContainer() {
        runner.withPropertyValues("dedup4j.storage.provider=azure")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("dedup4j.storage.azure.container is required"));
    }

    @Test
    void doesNotCreateWhenUnselected() {
        runner.withPropertyValues("dedup4j.storage.provider=local")
                .run(context -> assertThat(context).doesNotHaveBean(BlobContainerClient.class)
                        .doesNotHaveBean(AzureBlobStorage.class)
                        .doesNotHaveBean(AzureBlobStorageProperties.class)
                        .doesNotHaveBean(BlobStorage.class));
    }

    @Test
    void applicationStorageSuppressesAzureDefaultsWithoutAzureSettings() {
        BlobStorage custom = new LocalBlobStorage(new LocalBlobStorageProperties());

        runner.withBean(BlobStorage.class, () -> custom)
                .withPropertyValues("dedup4j.storage.provider=azure")
                .run(context -> assertThat(context).hasSingleBean(BlobStorage.class)
                        .doesNotHaveBean(AzureBlobStorage.class)
                        .doesNotHaveBean(AzureBlobStorageProperties.class)
                        .doesNotHaveBean(BlobContainerClient.class)
                        .satisfies(c -> assertThat(c.getBean(BlobStorage.class)).isSameAs(custom)));
    }

}
