package com.edem.dedup4j.history;

import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.reconcile.LogicalReferenceCountSource;
import com.edem.dedup4j.reconcile.ReconciliationRun;
import com.edem.dedup4j.reconcile.ReconciliationService;
import com.edem.dedup4j.reconcile.ReconciliationSummary;
import com.edem.dedup4j.reconcile.ScheduledReconciliation;
import com.edem.dedup4j.service.BlobDeduplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The starter's real auto-configuration started against PostgreSQL. */
@Tag("postgres")
@Testcontainers
class PostgresApplicationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path storageRoot;

    @Test
    void applicationStartsAndRecordsHistory() {
        runner(PostgresDatabases.create(POSTGRES)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("dedup4jHistoryDatabaseGuard");

            service(context).store(command("one upload"));
            UploadHistory history = context.getBean(UploadHistory.class);
            history.recordFailure("delete", null, "k", new IllegalStateException("boom"));

            assertThat(history.totals().uploads()).isEqualTo(1);
            assertThat(history.failuresAfter(0, 10)).singleElement()
                    .extracting(FailureRecord::recordedAt)
                    .isEqualTo(Instant.parse("2026-10-19T00:00:00.123456Z"));
        });
    }

    @Test
    void reconciliationPagesThroughContentOnPostgres() {
        Map<UUID, Long> expected = new HashMap<>();
        UUID dangling = UUID.randomUUID();
        runner(PostgresDatabases.create(POSTGRES))
                .withBean(LogicalReferenceCountSource.class, () -> () -> expected)
                .withPropertyValues("dedup4j.reconciliation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UUID matched = service(context).store(command("matched")).assetContentId();
                    UUID undercounted = service(context).store(command("undercounted")).assetContentId();
                    service(context).store(command("uncounted"));
                    expected.put(matched, 1L);
                    expected.put(undercounted, 2L);
                    expected.put(dangling, 1L);

                    ReconciliationSummary paged = new ReconciliationService(
                            context.getBean(AssetContentRepository.class)).summarize(() -> expected, 1, 10);
                    ReconciliationRun scheduled = context.getBean(ScheduledReconciliation.class).runNow();

                    for (ReconciliationSummary summary : List.of(paged, scheduled.summary())) {
                        assertThat(summary.checkedContentCount()).isEqualTo(3);
                        assertThat(summary.mismatchCount()).isEqualTo(2);
                        assertThat(summary.danglingReferenceSample()).containsExactly(dangling);
                    }
                });
    }

    /** Waiting on the dataset lock must not leave a stale snapshot, even when the database defaults to repeatable read. */
    @Test
    void concurrentUploadsUnderRepeatableReadDefaultAreSequenced() {
        runner(repeatableReadDatabase()).withUserConfiguration(HeldCommit.class).run(context -> {
            assertThat(context).hasNotFailed();
            runConcurrently(context, () -> service(context).store(command("first")),
                    () -> service(context).store(command("second")));

            assertThat(sequenceNumbers(context, "dedup4j_upload_event")).containsExactly(1L, 2L);
            assertThat(context.getBean(UploadHistory.class).totals().uploads()).isEqualTo(2);
        });
    }

    @Test
    void concurrentFailuresUnderRepeatableReadDefaultAreSequenced() {
        runner(repeatableReadDatabase()).withUserConfiguration(HeldCommit.class).run(context -> {
            assertThat(context).hasNotFailed();
            UploadHistory history = context.getBean(UploadHistory.class);
            runConcurrently(context, () -> history.recordFailure("upload", null, null, new IllegalStateException("a")),
                    () -> history.recordFailure("upload", null, null, new IllegalStateException("b")));

            assertThat(sequenceNumbers(context, "dedup4j_failure_event")).containsExactly(1L, 2L);
        });
    }

    private ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Consumer.class)
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=none",
                        "dedup4j.persistence.initialize-schema=always",
                        "dedup4j.storage.provider=local",
                        "dedup4j.storage.local.root-directory=" + storageRoot);
    }

    /** A fresh database whose sessions default to repeatable read. */
    private static String repeatableReadDatabase() {
        String url = PostgresDatabases.create(POSTGRES);
        String name = url.replaceFirst("^.*/([^/?]+)(\\?.*)?$", "$1");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("alter database " + name + " set default_transaction_isolation = 'repeatable read'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return url;
    }

    /**
     * Runs {@code first}, holds its history commit until {@code second} is waiting on the dataset lock, and
     * requires both to succeed.
     */
    private static void runConcurrently(ApplicationContext context, Runnable first, Runnable second) throws Exception {
        HeldCommit held = context.getBean(HeldCommit.class);
        held.armed.set(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(first);
            assertThat(held.holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> b = executor.submit(second);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static java.util.List<Long> sequenceNumbers(ApplicationContext context, String table) {
        return new JdbcTemplate(context.getBean(DataSource.class))
                .queryForList("select sequence_no from " + table + " order by sequence_no", Long.class);
    }

    private static BlobDeduplicationService service(ApplicationContext context) {
        return context.getBean(BlobDeduplicationService.class);
    }

    private static StoreBlobCommand command(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new StoreBlobCommand(new ByteArrayInputStream(bytes), "history.txt", "text/plain", bytes.length, Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Consumer {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-19T00:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }

    /**
     * Once armed, holds the first commit of a transaction that inserted a history row until another session is
     * waiting on a lock, so that session starts its transaction before the history row commits.
     */
    @Configuration(proxyBeanMethods = false)
    static class HeldCommit {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch holding = new CountDownLatch(1);

        @Bean
        static BeanPostProcessor holdHistoryCommit(org.springframework.beans.factory.ObjectProvider<HeldCommit> held) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(method, dataSource, args);
                                return result instanceof Connection connection
                                        ? holding(connection, held.getObject(), dataSource) : result;
                            });
                }
            };
        }

        private static Connection holding(Connection connection, HeldCommit held, DataSource dataSource) {
            AtomicBoolean insertedHistory = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                            String lower = sql.toLowerCase();
                            if (lower.startsWith("insert into dedup4j_upload_event")
                                    || lower.startsWith("insert into dedup4j_failure_event")) {
                                insertedHistory.set(true);
                            }
                        }
                        if (method.getName().equals("commit") && insertedHistory.getAndSet(false)
                                && held.armed.compareAndSet(true, false)) {
                            held.holding.countDown();
                            awaitLockWaiter(dataSource);
                        }
                        return invoke(method, connection, args);
                    });
        }

        private static void awaitLockWaiter(DataSource dataSource) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try (Connection monitor = ((DataSource) dataSource).getConnection();
                 Statement statement = monitor.createStatement()) {
                while (System.nanoTime() < deadline) {
                    try (var rows = statement.executeQuery("select count(*) from pg_locks where not granted")) {
                        rows.next();
                        if (rows.getInt(1) > 0) {
                            return;
                        }
                    }
                    Thread.sleep(20);
                }
            }
            throw new IllegalStateException("no session waited on the dataset lock");
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
