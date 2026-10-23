package com.edem.dedup4j.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class UploadHistoryRecordingTest {

    private static final byte[] CONTENT = "upload-history-recording".getBytes(StandardCharsets.UTF_8);
    private static final long N = CONTENT.length;

    @TempDir
    Path storageRoot;

    @TempDir
    Path databaseDirectory;

    private ApplicationContextRunner runner() {
        return runner("jdbc:h2:mem:dedup4j-recording-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Consumer.class)
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "dedup4j.persistence.initialize-schema=always",
                        "dedup4j.storage.provider=local",
                        "dedup4j.storage.local.root-directory=" + storageRoot);
    }

    @Test
    void newUploadRecordedOnce() {
        runner().run(context -> {
            service(context).store(command());

            assertTotals(history(context).totals(), 1, 0, 1, N, 0);
            assertThat(count(context, "dedup4j.uploads")).isEqualTo(1);
        });
    }

    @Test
    void duplicateUploadRecorded() {
        runner().run(context -> {
            service(context).store(command());
            service(context).store(command());

            assertTotals(history(context).totals(), 2, 1, 1, 2 * N, N);
        });
    }

    @Test
    void reuploadAfterFinalReleaseRecordedAsPhysicalWrite() {
        runner().run(context -> {
            BlobReference first = service(context).store(command());
            service(context).release(first.assetContentId());
            service(context).store(command());

            assertTotals(history(context).totals(), 2, 0, 2, 2 * N, 0);
        });
    }

    @Test
    void totalsSurviveContextRestart() {
        ApplicationContextRunner fileDatabase = runner(
                "jdbc:h2:file:" + databaseDirectory.resolve("history") + ";DB_CLOSE_ON_EXIT=FALSE");

        fileDatabase.run(context -> service(context).store(command()));

        fileDatabase.run(context -> assertThat(history(context).totals().uploads()).isEqualTo(1));
    }

    @Test
    void failedStorageWriteExcludedFromTotalsAndListed() {
        runner().run(context -> {
            SwitchableStorage storage = context.getBean(SwitchableStorage.class);
            storage.failPut.set(true);

            Throwable thrown = catchThrowable(() -> service(context).store(command()));

            assertThat(thrown).isSameAs(storage.putFailure);
            assertTotals(history(context).totals(), 0, 0, 0, 0, 0);
            assertThat(count(context, "dedup4j.uploads")).isZero();
            List<FailureRecord> failures = history(context).recentFailures(Instant.EPOCH, 10);
            assertThat(failures).singleElement().satisfies(failure -> {
                assertThat(failure.operation()).isEqualTo("upload");
                assertThat(failure.contentHash()).isEqualTo(sha256(CONTENT));
            });
        });
    }

    @Test
    void meterFailureAfterCommitDoesNotFailTheUpload() {
        runner().run(context -> {
            context.getBean(SimpleMeterRegistry.class).config().meterFilter(new MeterFilter() {
                @Override
                public Meter.Id map(Meter.Id id) {
                    if (id.getName().equals("dedup4j.uploads")) {
                        throw new IllegalStateException("meter registry unavailable");
                    }
                    return id;
                }
            });

            List<ILoggingEvent> warnings = captureWarnings(() -> service(context).store(command()));

            assertTotals(history(context).totals(), 1, 0, 1, N, 0);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).isEmpty();
            assertThat(warnings).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("event=metrics.upload.unrecorded");
            });
        });
    }

    @Test
    void duplicateRaceCountedOnceAsPhysicalWrite() {
        runner().withUserConfiguration(CompetingInsertConfiguration.class).run(context -> {
            BlobReference reference = service(context).store(command());

            assertThat(reference.duplicate()).isTrue();
            assertThat(context.getBean(CompetingInsert.class).inserted.get()).isTrue();
            assertTotals(history(context).totals(), 1, 1, 1, N, N);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).isEmpty();
            assertThat(count(context, "dedup4j.uploads")).isEqualTo(1);
            assertThat(count(context, "dedup4j.skipped.physical.writes")).isZero();
        });
    }

    @Test
    void duplicateRaceRestoresWinnerReleasedBeforeRetry() {
        runner().withUserConfiguration(CompetingInsertConfiguration.class).run(context -> {
            context.getBean(CompetingInsert.class).releaseWinner.set(true);

            BlobReference reference = service(context).store(oneShotCommand());

            try (var stored = service(context).get(reference.assetContentId()).content()) {
                assertThat(stored.readAllBytes()).isEqualTo(CONTENT);
            }
            assertTotals(history(context).totals(), 1, 0, 1, N, 0);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).isEmpty();
            assertThat(count(context, "dedup4j.uploads")).isEqualTo(1);
            assertThat(count(context, "dedup4j.skipped.physical.writes")).isZero();
        });
    }

    @Test
    void failedRestoreAfterDuplicateRaceRecordedAsFailure() {
        runner().withUserConfiguration(CompetingInsertConfiguration.class).run(context -> {
            CompetingInsert competingInsert = context.getBean(CompetingInsert.class);
            competingInsert.releaseWinner.set(true);
            competingInsert.failRestore.set(true);
            SwitchableStorage storage = context.getBean(SwitchableStorage.class);

            Throwable thrown = catchThrowable(() -> service(context).store(command()));

            assertThat(thrown).isSameAs(storage.putFailure);
            assertTotals(history(context).totals(), 0, 0, 0, 0, 0);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10))
                    .singleElement()
                    .satisfies(failure -> assertThat(failure.operation()).isEqualTo("upload"));

            storage.failPut.set(false);
            BlobReference reference = service(context).store(command());
            try (var stored = service(context).get(reference.assetContentId()).content()) {
                assertThat(stored.readAllBytes()).isEqualTo(CONTENT);
            }
        });
    }

    @Test
    void commitFailureLeavesMetersUnchanged() {
        runner().withUserConfiguration(FailingCommitConfiguration.class).run(context -> {
            FailingCommitTransactionManager transactions = context.getBean(FailingCommitTransactionManager.class);
            transactions.failNextCommit.set(true);

            assertThatThrownBy(() -> service(context).store(command()))
                    .isSameAs(transactions.commitFailure);

            assertThat(count(context, "dedup4j.uploads")).isZero();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "select count(*) from dedup4j_upload_event", Integer.class)).isZero();
            assertTotals(history(context).totals(), 0, 0, 0, 0, 0);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).singleElement()
                    .extracting(FailureRecord::operation).isEqualTo("upload");
        });
    }

    @Test
    void failureRecordingFailureRethrowsOriginal() {
        runner().withUserConfiguration(FailingFailureRecordingConfiguration.class).run(context -> {
            SwitchableStorage storage = context.getBean(SwitchableStorage.class);
            storage.failPut.set(true);

            List<ILoggingEvent> warnings = captureWarnings(
                    () -> assertThat(catchThrowable(() -> service(context).store(command())))
                            .isSameAs(storage.putFailure)
                            .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1)));

            assertUnrecordedWarning(warnings, "upload");
        });
    }

    @Test
    void deleteFailureRecorded() {
        runner().run(context -> {
            SwitchableStorage storage = context.getBean(SwitchableStorage.class);
            BlobReference reference = service(context).store(command());
            storage.failDelete.set(true);

            Throwable thrown = catchThrowable(() -> service(context).release(reference.assetContentId()));

            assertThat(thrown).isSameAs(storage.deleteFailure);
            assertThat(history(context).recentFailures(Instant.EPOCH, 10)).singleElement().satisfies(failure -> {
                assertThat(failure.operation()).isEqualTo("delete");
                assertThat(failure.objectKey()).isEqualTo(reference.objectKey());
            });
        });
    }

    @Test
    void deleteFailureRecordingFailureRethrowsOriginal() {
        runner().withUserConfiguration(FailingFailureRecordingConfiguration.class).run(context -> {
            SwitchableStorage storage = context.getBean(SwitchableStorage.class);
            BlobReference reference = service(context).store(command());
            storage.failDelete.set(true);

            List<ILoggingEvent> warnings = captureWarnings(
                    () -> assertThat(catchThrowable(() -> service(context).release(reference.assetContentId())))
                            .isSameAs(storage.deleteFailure)
                            .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1)));

            assertUnrecordedWarning(warnings, "delete");
        });
    }

    private static List<ILoggingEvent> captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(SpringTransactionalBlobDeduplicationService.class);
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
        return appender.list;
    }

    private static void assertUnrecordedWarning(List<ILoggingEvent> events, String operation) {
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("event=history.failure.unrecorded", "operation=" + operation);
        });
    }

    private static void assertTotals(
            HistoryTotals totals, long uploads, long duplicates, long physicalWrites,
            long logicalBytes, long avoidedBytes) {
        assertThat(List.of(totals.uploads(), totals.duplicates(), totals.physicalWrites(),
                totals.logicalBytes(), totals.avoidedBytes()))
                .containsExactly(uploads, duplicates, physicalWrites, logicalBytes, avoidedBytes);
    }

    private static BlobDeduplicationService service(ApplicationContext context) {
        return context.getBean(BlobDeduplicationService.class);
    }

    private static UploadHistory history(ApplicationContext context) {
        return context.getBean(UploadHistory.class);
    }

    private static long count(ApplicationContext context, String name) {
        Counter counter = context.getBean(SimpleMeterRegistry.class).find(name).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static StoreBlobCommand command() {
        return new StoreBlobCommand(new ByteArrayInputStream(CONTENT), "history.txt", "text/plain", N, Map.of());
    }

    /** A stream that fails if anything reads it again after it was closed. */
    private static StoreBlobCommand oneShotCommand() {
        ByteArrayInputStream once = new ByteArrayInputStream(CONTENT) {
            private boolean closed;

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                if (closed) {
                    throw new IllegalStateException("upload stream read after close");
                }
                return super.read(buffer, offset, length);
            }
        };
        return new StoreBlobCommand(once, "history.txt", "text/plain", N, Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Consumer {

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SwitchableStorage switchableStorage(org.springframework.core.env.Environment environment) {
            LocalBlobStorageProperties properties = new LocalBlobStorageProperties();
            properties.setRootDirectory(Path.of(environment.getRequiredProperty("dedup4j.storage.local.root-directory")));
            return new SwitchableStorage(new LocalBlobStorage(properties));
        }
    }

    static final class SwitchableStorage implements BlobStorage {

        final AtomicBoolean failPut = new AtomicBoolean();
        final AtomicBoolean failDelete = new AtomicBoolean();
        final BlobStorageException putFailure = new BlobStorageException("put failed");
        final BlobStorageException deleteFailure = new BlobStorageException("delete failed");
        private final BlobStorage delegate;

        SwitchableStorage(BlobStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public StoredBlob put(PutBlobRequest request) {
            if (failPut.get()) {
                throw putFailure;
            }
            return delegate.put(request);
        }

        @Override
        public BlobResource get(String objectKey) {
            return delegate.get(objectKey);
        }

        @Override
        public void delete(String objectKey) {
            if (failDelete.get()) {
                throw deleteFailure;
            }
            delegate.delete(objectKey);
        }

        @Override
        public boolean exists(String objectKey) {
            return delegate.exists(objectKey);
        }
    }

    /** On the first persist, another "request" commits the same content identity first. */
    @Configuration(proxyBeanMethods = false)
    static class CompetingInsertConfiguration {

        @Bean
        CompetingInsert competingInsert(ObjectProvider<DataSource> dataSource, ObjectProvider<BlobStorage> storage) {
            return new CompetingInsert(dataSource, storage);
        }

        @Bean
        static BeanPostProcessor competingInsertBeforePersist(ObjectProvider<CompetingInsert> competingInsert) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof EntityManager)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                            new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                                if (method.getName().equals("persist") && args[0] instanceof AssetContent content) {
                                    competingInsert.getObject().insertOnce(content);
                                }
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                }
            };
        }
    }

    static final class CompetingInsert {

        final AtomicBoolean inserted = new AtomicBoolean();
        /** When set, the competing content is released to zero and its object deleted before the retry. */
        final AtomicBoolean releaseWinner = new AtomicBoolean();
        /** When set with {@link #releaseWinner}, the retry's write-back of the object fails. */
        final AtomicBoolean failRestore = new AtomicBoolean();
        private final ObjectProvider<DataSource> dataSource;
        private final ObjectProvider<BlobStorage> storage;

        CompetingInsert(ObjectProvider<DataSource> dataSource, ObjectProvider<BlobStorage> storage) {
            this.dataSource = dataSource;
            this.storage = storage;
        }

        void insertOnce(AssetContent content) throws Exception {
            if (!inserted.compareAndSet(false, true)) {
                return;
            }
            Timestamp now = Timestamp.from(Instant.now());
            try (Connection connection = dataSource.getObject().getConnection();
                 PreparedStatement insert = connection.prepareStatement("""
                         insert into dedup4j_asset_content (id, hash_algorithm, content_hash, size_bytes,
                             object_key, storage_provider, bucket_or_container, ref_count,
                             created_at, updated_at, version)
                         values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""")) {
                connection.setAutoCommit(true);
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, content.getHashAlgorithm());
                insert.setString(3, content.getContentHash());
                insert.setLong(4, content.getSizeBytes());
                insert.setString(5, content.getObjectKey());
                insert.setString(6, content.getStorageProvider());
                insert.setString(7, content.getBucketOrContainer());
                insert.setLong(8, releaseWinner.get() ? 0 : 1);
                insert.setTimestamp(9, now);
                insert.setTimestamp(10, now);
                insert.executeUpdate();
            }
            if (releaseWinner.get()) {
                storage.getObject().delete(content.getObjectKey());
                if (failRestore.get()) {
                    ((SwitchableStorage) storage.getObject()).failPut.set(true);
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingCommitConfiguration {

        @Bean
        FailingCommitTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new FailingCommitTransactionManager(entityManagerFactory);
        }
    }

    static final class FailingCommitTransactionManager extends JpaTransactionManager {

        final AtomicBoolean failNextCommit = new AtomicBoolean();
        final TransactionSystemException commitFailure = new TransactionSystemException("commit failed");

        FailingCommitTransactionManager(EntityManagerFactory entityManagerFactory) {
            super(entityManagerFactory);
            setRollbackOnCommitFailure(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit.compareAndSet(true, false)) {
                throw commitFailure;
            }
            super.doCommit(status);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingFailureRecordingConfiguration {

        @Bean
        static BeanPostProcessor failingFailureRecording() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof UploadHistory)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(UploadHistory.class.getClassLoader(),
                            new Class<?>[]{UploadHistory.class}, (proxy, method, args) -> {
                                if (method.getName().equals("recordFailure")) {
                                    throw new IllegalStateException("failure log unavailable");
                                }
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                }
            };
        }
    }
}
