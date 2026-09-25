package com.edem.blobhelper.autoconfigure;

import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.blobhelper.service.BlobDeduplicationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class BlobHelperContextStartTest {

    private static final String CONTENT = "consumer-discovery-content";

    @TempDir
    Path storageRoot;

    private ApplicationContextRunner consumerRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(MinimalConsumer.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:blob-helper-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "blob-helper.storage.provider=local",
                        "blob-helper.storage.local.root-directory=" + storageRoot
                );
    }

    private ApplicationContextRunner validatedConsumerRunner() {
        return consumerRunner().withPropertyValues("spring.jpa.hibernate.ddl-auto=validate");
    }

    @Test
    void startsWithOnlyConsumerDatabaseAndStorageProperties() {
        consumerRunner().run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(BlobDeduplicationService.class)
                    .hasSingleBean(JdbcTemplate.class);

            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "select count(*) from blob_helper_asset_content", Integer.class
            )).isZero();
        });
    }

    @Test
    void startsWithExplicitHibernateValidationAgainstPackagedSchema() {
        validatedConsumerRunner().run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(BlobDeduplicationService.class));
    }

    @Test
    void startsWithoutSpringDataRepositoryRegistration() {
        validatedConsumerRunner()
                .withPropertyValues("spring.data.jpa.repositories.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(BlobDeduplicationService.class));
    }

    @Test
    void storeReadRetainAndReleaseUseConsumerInfrastructure() {
        validatedConsumerRunner().run(context -> {
            BlobDeduplicationService service = context.getBean(BlobDeduplicationService.class);
            BlobReference reference = service.store(command(CONTENT));

            try (BlobResource resource = service.get(reference.assetContentId())) {
                assertThat(resource.content().readAllBytes())
                        .isEqualTo(CONTENT.getBytes(StandardCharsets.UTF_8));
            }

            service.retain(reference.assetContentId());
            assertThat(refCount(context)).isEqualTo(2);
            service.release(reference.assetContentId());
            assertThat(refCount(context)).isEqualTo(1);
            service.release(reference.assetContentId());

            assertThat(rowCount(context)).isEqualTo(1);
            assertThat(refCount(context)).isZero();
            assertThat(regularFileCount(storageRoot)).isZero();
        });
    }

    @Test
    void concurrentIdenticalUploadsConvergeOnOneRowAndObject() throws Exception {
        consumerRunner()
                .withUserConfiguration(ForcedRaceConfiguration.class)
                .run(context -> {
                    BlobDeduplicationService service = context.getBean(BlobDeduplicationService.class);
                    RaceGate raceGate = context.getBean(RaceGate.class);
                    CyclicBarrier beforeUpload = new CyclicBarrier(2);
                    ExecutorService workers = Executors.newFixedThreadPool(2);
                    try {
                        List<Future<BlobReference>> results = List.of(
                                workers.submit(() -> uploadAfterBarrier(service, beforeUpload)),
                                workers.submit(() -> uploadAfterBarrier(service, beforeUpload))
                        );

                        BlobReference first = get(results.get(0));
                        BlobReference second = get(results.get(1));
                        assertThat(first.assetContentId()).isEqualTo(second.assetContentId());
                        assertThat(first.objectKey()).isEqualTo(second.objectKey());
                        assertThat(List.of(first.duplicate(), second.duplicate()))
                                .containsExactlyInAnyOrder(false, true);
                        assertThat(raceGate.flushes()).isEqualTo(2);
                        assertThat(raceGate.duplicateExceptions()).isEqualTo(1);
                        assertThat(rowCount(context)).isEqualTo(1);
                        assertThat(refCount(context)).isEqualTo(2);
                        assertThat(regularFileCount(storageRoot)).isEqualTo(1);
                    } finally {
                        workers.shutdownNow();
                        workers.awaitTermination(10, TimeUnit.SECONDS);
                    }
                });
    }

    private static BlobReference uploadAfterBarrier(
            BlobDeduplicationService service,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return service.store(command(CONTENT));
    }

    private static BlobReference get(Future<BlobReference> result)
            throws InterruptedException, ExecutionException, TimeoutException {
        return result.get(30, TimeUnit.SECONDS);
    }

    private static StoreBlobCommand command(String content) {
        return new StoreBlobCommand(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "consumer-test.txt",
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8).length,
                Map.of()
        );
    }

    private static int rowCount(org.springframework.context.ApplicationContext context) {
        return context.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from blob_helper_asset_content", Integer.class
        );
    }

    private static int refCount(org.springframework.context.ApplicationContext context) {
        return context.getBean(JdbcTemplate.class).queryForObject(
                "select ref_count from blob_helper_asset_content", Integer.class
        );
    }

    private static long regularFileCount(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MinimalConsumer {
    }

    @Configuration(proxyBeanMethods = false)
    static class ForcedRaceConfiguration {

        @Bean
        RaceGate raceGate() {
            return new RaceGate();
        }

        @Bean
        static BeanPostProcessor gateConcurrentFlushes(RaceGate raceGate) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof EntityManager)) {
                        return bean;
                    }
                    InvocationHandler handler = (proxy, method, args) -> {
                        if (method.getName().equals("flush") && method.getParameterCount() == 0) {
                            raceGate.recordFlush();
                            raceGate.awaitFlushPair();
                        }
                        try {
                            return method.invoke(bean, args);
                        } catch (InvocationTargetException failure) {
                            if (hasDuplicateSqlState(failure.getCause())) {
                                raceGate.recordDuplicateException();
                            }
                            throw failure.getCause();
                        }
                    };
                    return Proxy.newProxyInstance(
                            EntityManager.class.getClassLoader(),
                            new Class<?>[]{EntityManager.class},
                            handler
                    );
                }
            };
        }
    }

    static final class RaceGate {

        private final CyclicBarrier flushBarrier = new CyclicBarrier(2);
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger duplicateExceptions = new AtomicInteger();

        void recordFlush() {
            flushes.incrementAndGet();
        }

        void awaitFlushPair() throws Exception {
            flushBarrier.await(10, TimeUnit.SECONDS);
        }

        void recordDuplicateException() {
            duplicateExceptions.incrementAndGet();
        }

        int flushes() {
            return flushes.get();
        }

        int duplicateExceptions() {
            return duplicateExceptions.get();
        }
    }

    private static boolean hasDuplicateSqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
