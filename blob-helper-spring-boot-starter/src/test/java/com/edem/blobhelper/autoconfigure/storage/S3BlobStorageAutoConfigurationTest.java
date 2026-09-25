package com.edem.blobhelper.autoconfigure.storage;

import com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration;
import com.edem.dedup4j.storage.s3.S3BlobStorage;
import com.edem.dedup4j.storage.s3.S3BlobStorageProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class S3BlobStorageAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(S3BlobStorageAutoConfiguration.class, BlobHelperAutoConfiguration.class));
    private final ApplicationContextRunner standaloneRunner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(S3BlobStorageAutoConfiguration.class));

    @Test
    void bindsMinioOverridesIntoClientAndProperties() {
        standaloneRunner.withPropertyValues("blob-helper.storage.provider=s3", "blob-helper.storage.s3.bucket=media",
                        "blob-helper.storage.s3.region=eu-west-1", "blob-helper.storage.s3.endpoint=http://localhost:9000",
                        "blob-helper.storage.s3.path-style=true")
                .run(context -> {
                    S3BlobStorageProperties properties = context.getBean(S3BlobStorageProperties.class);
                    assertThat(properties.getRegion()).isEqualTo("eu-west-1");
                    assertThat(properties.getEndpointOverride()).isEqualTo(URI.create("http://localhost:9000"));
                    assertThat(properties.isPathStyleAccess()).isTrue();
                    var configuration = context.getBean(S3Client.class).serviceClientConfiguration();
                    assertThat(configuration.endpointOverride()).contains(URI.create("http://localhost:9000"));
                    assertThat(configuration.region()).isEqualTo(Region.EU_WEST_1);
                    // S3ServiceClientConfiguration does not expose forcePathStyle; the
                    // translated properties assertion above is the SDK-supported evidence.
                });
    }

    @Test
    void createsDefaultClientWithoutNetwork() {
        String previous = System.getProperty("aws.region");
        try {
            System.setProperty("aws.region", "us-east-1");
            runner.withPropertyValues("blob-helper.storage.provider=s3", "blob-helper.storage.s3.bucket=media")
                    .run(context -> assertThat(context).hasSingleBean(S3BlobStorage.class)
                            .hasSingleBean(S3Client.class)
                            .satisfies(c -> assertThat(c.getBean(S3BlobStorageProperties.class).getBucket()).isEqualTo("media")));
        } finally {
            if (previous == null) System.clearProperty("aws.region"); else System.setProperty("aws.region", previous);
        }
    }

    @Test
    void reusesApplicationClient() {
        S3Client client = S3Client.builder().region(Region.US_EAST_1).build();
        runner.withBean(S3Client.class, () -> client)
                .withPropertyValues("blob-helper.storage.provider=s3", "blob-helper.storage.s3.bucket=media")
                .run(context -> {
                    assertThat(context.getBean(S3Client.class)).isSameAs(client);
                    assertThat(ReflectionTestUtils.getField(context.getBean(S3BlobStorage.class), "client"))
                            .isSameAs(client);
                });
    }

    @Test
    void applicationClientIsClosedExactlyOnceBySpring() {
        AtomicInteger closes = new AtomicInteger();
        S3Client client = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{S3Client.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) { closes.incrementAndGet(); return null; }
                    if (method.getName().equals("toString")) { return "applicationS3Client"; }
                    if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
                    if (method.getName().equals("equals")) { return proxy == args[0]; }
                    throw new AssertionError("Unexpected client method during startup: " + method.getName());
                });
        applicationClient = client;
        try {
            runner.withUserConfiguration(ApplicationClientConfiguration.class)
                    .withPropertyValues("blob-helper.storage.provider=s3", "blob-helper.storage.s3.bucket=media")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.close();
                    });
            assertThat(closes).hasValue(1);
        } finally {
            applicationClient = null;
        }
    }

    @Test
    void springDestroysDefaultClientButNotStorageAdapter() {
        String previous = System.getProperty("aws.region");
        try {
            System.setProperty("aws.region", "us-east-1");
            runner.withPropertyValues("blob-helper.storage.provider=s3", "blob-helper.storage.s3.bucket=media").run(context -> {
                assertThat(context.getSourceApplicationContext().getBeanFactory().getBeanDefinition("blobHelperS3Client")
                        .getDestroyMethodName()).isEqualTo("(inferred)");
                assertThat(context.getSourceApplicationContext().getBeanFactory().getBeanDefinition("s3BlobStorage")
                        .getDestroyMethodName()).isEmpty();
                context.close();
            });
        } finally {
            if (previous == null) System.clearProperty("aws.region"); else System.setProperty("aws.region", previous);
        }
    }

    @Test
    void requiresBucket() {
        runner.withPropertyValues("blob-helper.storage.provider=s3")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("blob-helper.storage.s3.bucket is required"));
    }

    @Test
    void applicationStorageSkipsEntireS3GraphWithoutCloudProperties() {
        BlobStorage custom = new LocalBlobStorage(new LocalBlobStorageProperties());
        runner.withBean(BlobStorage.class, () -> custom)
                .withPropertyValues("blob-helper.storage.provider=s3")
                .run(context -> {
                    assertThat(context.getBean(BlobStorage.class)).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(S3Client.class)
                            .doesNotHaveBean(S3BlobStorageProperties.class)
                            .doesNotHaveBean(S3BlobStorage.class);
                });
    }

    @Test
    void doesNotCreateWhenUnselected() {
        standaloneRunner
                .withPropertyValues("blob-helper.storage.provider=local")
                .run(context -> assertThat(context).doesNotHaveBean(S3Client.class)
                        .doesNotHaveBean(S3BlobStorage.class));
    }

    private static S3Client applicationClient;

    @Configuration(proxyBeanMethods = false)
    static class ApplicationClientConfiguration {

        @Bean(destroyMethod = "close")
        S3Client applicationClient() {
            return S3BlobStorageAutoConfigurationTest.applicationClient;
        }
    }

}
