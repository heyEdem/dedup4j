package com.edem.dedup4j.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.JpaUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Transaction managers that reject custom isolation levels (plain JPA dialect, JTA without
 * allowCustomIsolationLevels) must keep working; history appends fall back to the database default.
 */
class UnsupportedIsolationFallbackTest {

    private static final byte[] CONTENT = "unsupported-isolation".getBytes(StandardCharsets.UTF_8);
    private static final long N = CONTENT.length;
    private static final String FALLBACK_EVENT = "event=history.isolation.unsupported";

    @TempDir
    Path storageRoot;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UploadHistoryRecordingTest.Consumer.class, RejectingConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:dedup4j-isolation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "dedup4j.persistence.initialize-schema=always",
                        "dedup4j.storage.provider=local",
                        "dedup4j.storage.local.root-directory=" + storageRoot);
    }

    @Test
    void retainGetLocationAndReleaseNeverRequestCustomIsolation() {
        runner().run(context -> {
            List<BlobReference> stored = new ArrayList<>();
            assertSingleFallbackWarning(captureWarnings(SpringTransactionalBlobDeduplicationService.class,
                    () -> stored.add(service(context).store(command()))));
            BlobReference reference = stored.getFirst();
            IsolationRejectingTransactionManager transactions = transactions(context);
            transactions.requested.clear();

            service(context).retain(reference.assetContentId());
            try (InputStream content = service(context).get(reference.assetContentId()).content()) {
                assertThat(content.readAllBytes()).isEqualTo(CONTENT);
            }
            assertThat(service(context).location(reference.assetContentId())).isNotNull();
            service(context).release(reference.assetContentId());

            assertThat(transactions.requested).isNotEmpty()
                    .containsOnly(TransactionDefinition.ISOLATION_DEFAULT);
        });
    }

    @Test
    void storeFallsBackOnceAndCountsEachUploadOnce() {
        runner().run(context -> {
            List<ILoggingEvent> first = captureWarnings(SpringTransactionalBlobDeduplicationService.class,
                    () -> service(context).store(command()));

            assertThat(transactions(context).requested).contains(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(history(context).totals().uploads()).isEqualTo(1);
            assertThat(count(context, "dedup4j.uploads")).isEqualTo(1);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).isEmpty();
            transactions(context).requested.clear();

            List<ILoggingEvent> later = captureWarnings(SpringTransactionalBlobDeduplicationService.class, () -> {
                service(context).store(command());
                service(context).store(command());
            });

            assertSingleFallbackWarning(first);
            assertThat(later).isEmpty();
            assertThat(transactions(context).requested).containsOnly(TransactionDefinition.ISOLATION_DEFAULT);
            assertThat(history(context).totals().uploads()).isEqualTo(3);
            assertThat(count(context, "dedup4j.uploads")).isEqualTo(3);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).isEmpty();
        });
    }

    @Test
    void failureRowsPersistedAfterOneFallbackWarning() {
        runner().run(context -> {
            UploadHistoryRecordingTest.SwitchableStorage storage =
                    context.getBean(UploadHistoryRecordingTest.SwitchableStorage.class);
            storage.failPut.set(true);
            List<ILoggingEvent> serviceWarnings = new ArrayList<>();

            List<ILoggingEvent> historyWarnings = captureWarnings(JpaUploadHistory.class,
                    () -> serviceWarnings.addAll(captureWarnings(SpringTransactionalBlobDeduplicationService.class, () -> {
                        assertThat(catchThrowable(() -> service(context).store(command()))).isSameAs(storage.putFailure);
                        assertThat(catchThrowable(() -> service(context).store(command()))).isSameAs(storage.putFailure);
                    })));

            assertSingleFallbackWarning(historyWarnings);
            assertSingleFallbackWarning(serviceWarnings);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).hasSize(2)
                    .extracting(FailureRecord::operation).containsOnly("upload");
        });
    }

    @Test
    void historyDisabledNeverRequestsCustomIsolation() {
        runner().withPropertyValues("dedup4j.history.enabled=false").run(context -> {
            List<ILoggingEvent> warnings = captureWarnings(SpringTransactionalBlobDeduplicationService.class, () -> {
                BlobReference reference = service(context).store(command());
                service(context).retain(reference.assetContentId());
                closeQuietly(service(context).get(reference.assetContentId()).content());
                service(context).location(reference.assetContentId());
                service(context).release(reference.assetContentId());
            });

            assertThat(warnings).isEmpty();
            assertThat(transactions(context).requested).isNotEmpty()
                    .containsOnly(TransactionDefinition.ISOLATION_DEFAULT);
        });
    }

    private static void assertSingleFallbackWarning(List<ILoggingEvent> events) {
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(FALLBACK_EVENT);
        });
    }

    private static List<ILoggingEvent> captureWarnings(Class<?> source, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.setAdditive(true);
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    private static void closeQuietly(InputStream content) {
        try (content) {
            content.transferTo(OutputStream.nullOutputStream());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static BlobDeduplicationService service(ApplicationContext context) {
        return context.getBean(BlobDeduplicationService.class);
    }

    private static UploadHistory history(ApplicationContext context) {
        return context.getBean(UploadHistory.class);
    }

    private static IsolationRejectingTransactionManager transactions(ApplicationContext context) {
        return context.getBean(IsolationRejectingTransactionManager.class);
    }

    private static long count(ApplicationContext context, String name) {
        Counter counter = context.getBean(SimpleMeterRegistry.class).find(name).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private static StoreBlobCommand command() {
        return new StoreBlobCommand(new ByteArrayInputStream(CONTENT), "isolation.txt", "text/plain", N, Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    static class RejectingConfiguration {

        @Bean
        IsolationRejectingTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new IsolationRejectingTransactionManager(entityManagerFactory);
        }
    }

    /** Plain JPA dialect: begin throws InvalidIsolationLevelException for any non-default isolation. */
    static final class IsolationRejectingTransactionManager extends JpaTransactionManager {

        final List<Integer> requested = Collections.synchronizedList(new ArrayList<>());

        IsolationRejectingTransactionManager(EntityManagerFactory entityManagerFactory) {
            super(entityManagerFactory);
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            // afterPropertiesSet copies Hibernate's dialect, which accepts custom isolation.
            setJpaDialect(new DefaultJpaDialect());
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            requested.add(definition.getIsolationLevel());
            super.doBegin(transaction, definition);
        }
    }
}
