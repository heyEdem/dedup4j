package com.edem.dedup4j.autoconfigure.storage;

import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBlobStorageAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(LocalBlobStorageAutoConfiguration.class));

    @Test
    void createsConfiguredLocalStorage() {
        runner.withPropertyValues("dedup4j.storage.provider=local", "dedup4j.storage.local.root-directory=build/blobs")
                .run(context -> assertThat(context).hasSingleBean(LocalBlobStorage.class)
                        .hasSingleBean(BlobStorage.class)
                        .hasSingleBean(LocalBlobStorageProperties.class)
                        .satisfies(c -> assertThat(c.getBean(LocalBlobStorageProperties.class).getRootDirectory())
                                .isEqualTo(Path.of("build/blobs"))));
    }

    @Test
    void createsDefaultLocalStorageWhenSelected() {
        runner.withPropertyValues("dedup4j.storage.provider=local")
                .run(context -> assertThat(context).hasSingleBean(LocalBlobStorage.class)
                        .hasSingleBean(LocalBlobStorageProperties.class)
                        .satisfies(c -> assertThat(c.getBean(LocalBlobStorageProperties.class).getRootDirectory())
                                .isEqualTo(Path.of("dedup4j-storage"))));
    }

    @Test
    void applicationStorageOverridesDefault() {
        BlobStorage custom = new LocalBlobStorage(new LocalBlobStorageProperties());
        runner.withBean(BlobStorage.class, () -> custom)
                .withPropertyValues("dedup4j.storage.provider=local")
                .run(context -> assertThat(context).hasSingleBean(BlobStorage.class)
                        .doesNotHaveBean("localBlobStorage")
                        .doesNotHaveBean(LocalBlobStorageProperties.class)
                        .satisfies(c -> assertThat(c.getBean(BlobStorage.class)).isSameAs(custom)));
    }

    @Test
    void doesNotCreateLocalStorageWhenUnselected() {
        runner.withPropertyValues("dedup4j.storage.provider=s3")
                .run(context -> assertThat(context).doesNotHaveBean(LocalBlobStorage.class)
                        .doesNotHaveBean(LocalBlobStorageProperties.class)
                        .doesNotHaveBean(BlobStorage.class));
    }

}
