package com.edem.dedup4j.autoconfigure.history;

import com.edem.dedup4j.autoconfigure.persistence.Dedup4jPersistenceAutoConfiguration;
import com.edem.dedup4j.history.DefaultInstanceId;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.JpaUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.UploadContribution;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Dedup4jHistoryAutoConfigurationTest {

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Dedup4jPersistenceAutoConfiguration.class,
                    Dedup4jHistoryAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withPropertyValues("spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=none");

    @Test
    void historyEnabledByDefaultUsesJpa() {
        runner.withUserConfiguration(H2Configuration.class).run(context -> {
            assertThat(context).hasNotFailed();
            UploadHistory history = context.getBean(UploadHistory.class);
            assertThat(history).isInstanceOf(JpaUploadHistory.class);
            assertThat(history.persistent()).isTrue();
            assertThat(history.totals().scope()).isEqualTo("all-time");
        });
    }

    @Test
    void historyDisabledUsesCountersAndSkipsChangelog() {
        runner.withUserConfiguration(H2Configuration.class)
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("dedup4j.history.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Dedup4jMetrics metrics = new Dedup4jMetrics(context.getBean(SimpleMeterRegistry.class));
                    metrics.recordUpload(10, false, true);
                    metrics.recordUpload(20, false, true);
                    metrics.recordUpload(30, true, false);
                    HistoryTotals totals = context.getBean(UploadHistory.class).totals();
                    assertThat(totals.scope()).isEqualTo("since-start");
                    assertThat(totals.datasetId()).isNull();
                    assertThat(totals.uploads()).isEqualTo(3);
                    assertThat(totals.duplicates()).isEqualTo(1);
                    assertThat(totals.physicalWrites()).isEqualTo(2);
                    assertThat(totals.logicalBytes()).isEqualTo(60);
                    assertThat(totals.avoidedBytes()).isEqualTo(30);
                    assertThat(context.getBean(UploadHistory.class)).isInstanceOf(DisabledUploadHistory.class);
                    assertThat(context.getBeansOfType(SpringLiquibase.class)).doesNotContainKey("dedup4jHistoryLiquibase");
                    assertThat(new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(
                            "select count(*) from information_schema.tables where table_name = 'DEDUP4J_UPLOAD_EVENT'",
                            Integer.class)).isZero();
                });
    }

    @Test
    void unsupportedDatabaseFailsStartup() {
        runner.withUserConfiguration(MySqlConfiguration.class)
                .withPropertyValues("dedup4j.persistence.initialize-schema=never")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("dedup4j history requires PostgreSQL or H2")
                        .hasMessageContaining("dedup4j.history.enabled=false"));
    }

    @Test
    void unsupportedDatabaseIsRejectedBeforeAnyMigrationRuns() {
        runner.withUserConfiguration(MySqlConfiguration.class)
                .withPropertyValues("dedup4j.persistence.initialize-schema=always")
                .run(context -> {
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasMessageContaining("dedup4j history requires PostgreSQL or H2");
                    assertThat(new JdbcTemplate(MySqlConfiguration.DELEGATE.get()).queryForObject(
                            "select count(*) from information_schema.tables where table_name like 'DEDUP4J%'",
                            Integer.class)).isZero();
                });
    }

    @Test
    void recordUploadOutsideTransactionFailsFast() {
        runner.withUserConfiguration(H2Configuration.class).run(context ->
                assertThatThrownBy(() -> context.getBean(UploadHistory.class).recordUpload(
                        UploadContribution.completed(UUID.randomUUID(), "SHA-256", "hash", 10, false, true)))
                        .isInstanceOf(IllegalTransactionStateException.class));
    }

    @Test
    void missingHistoryTablesFailStartupWithGuidance() {
        runner.withUserConfiguration(AssetContentOnlyConfiguration.class)
                .withPropertyValues("dedup4j.persistence.initialize-schema=never")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("classpath:db/dedup4j/history/db.changelog-history.yaml"));
    }

    @Test
    void instanceIdDefaultsToTheSharedProcessId() {
        assertThat(recordedInstanceId(runner)).isEqualTo(DefaultInstanceId.value());
    }

    @Test
    void instanceIdFallsBackToTheManagementInstanceId() {
        assertThat(recordedInstanceId(runner.withPropertyValues("dedup4j.management.instance-id=management-id")))
                .isEqualTo("management-id");
    }

    @Test
    void historyInstanceIdWins() {
        assertThat(recordedInstanceId(runner.withPropertyValues(
                "dedup4j.management.instance-id=management-id", "dedup4j.history.instance-id=history-id")))
                .isEqualTo("history-id");
    }

    private static String recordedInstanceId(ApplicationContextRunner runner) {
        java.util.concurrent.atomic.AtomicReference<String> instanceId = new java.util.concurrent.atomic.AtomicReference<>();
        runner.withUserConfiguration(H2Configuration.class).run(context -> {
            UploadHistory history = context.getBean(UploadHistory.class);
            history.recordFailure("upload", null, null, new RuntimeException("boom"));
            instanceId.set(history.failuresAfter(0, 1).getFirst().instanceId());
        });
        return instanceId.get();
    }

    @Test
    void failureMessageNullAndLongHandled() {
        runner.withUserConfiguration(H2Configuration.class).run(context -> {
            UploadHistory history = context.getBean(UploadHistory.class);
            history.recordFailure("upload", null, null, new RuntimeException());
            history.recordFailure("upload", "abc", "key", new RuntimeException("x".repeat(5000)));

            List<FailureRecord> failures = history.failuresAfter(0, 10);
            assertThat(failures).extracting(FailureRecord::message)
                    .containsExactly("RuntimeException", "x".repeat(1000));
            assertThat(failures).extracting(FailureRecord::sequenceNo).containsExactly(1L, 2L);
        });
    }

    @Test
    void recordedAtTruncatedToMicros() {
        runner.withUserConfiguration(H2Configuration.class, FixedClockConfiguration.class).run(context -> {
            UploadHistory history = context.getBean(UploadHistory.class);
            history.recordFailure("delete", null, "k", new IllegalStateException("boom"));

            assertThat(history.failuresAfter(0, 10)).singleElement()
                    .extracting(FailureRecord::recordedAt)
                    .isEqualTo(Instant.parse("2026-10-19T00:00:00.123456Z"));
            assertThat(history.recentFailures(Instant.parse("2026-10-18T00:00:00Z"), 10)).hasSize(1);
        });
    }

    @Test
    void failureIsRecordedInItsOwnTransactionAndUploadJoinsTheCaller() {
        runner.withUserConfiguration(H2Configuration.class).run(context -> {
            UploadHistory history = context.getBean(UploadHistory.class);
            TransactionTemplate outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            outer.executeWithoutResult(status -> {
                history.recordFailure("upload", null, null, new RuntimeException("kept"));
                status.setRollbackOnly();
            });
            outer.executeWithoutResult(status -> {
                history.recordUpload(UploadContribution.completed(
                        UUID.randomUUID(), "SHA-256", "hash", 10, false, true));
                status.setRollbackOnly();
            });

            assertThat(history.failuresAfter(0, 10)).hasSize(1);
            assertThat(history.totals().uploads()).isZero();

            outer.executeWithoutResult(status -> history.recordUpload(UploadContribution.completed(
                    UUID.randomUUID(), "SHA-256", "hash", 10, true, false)));
            assertThat(history.totals().uploads()).isEqualTo(1);
            assertThat(history.totals().datasetId()).isNotNull();
        });
    }

    @Test
    void historyStartsOnH2WithLowerCaseIdentifiers() {
        runner.withUserConfiguration(LowerCaseH2Configuration.class)
                .withPropertyValues("dedup4j.persistence.initialize-schema=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UploadHistory.class).totals().scope()).isEqualTo("all-time");
                });
    }

    @Test
    void historyAndAssetMigrationsUseDistinctTrackingTables() {
        runner.withUserConfiguration(H2Configuration.class)
                .withPropertyValues("dedup4j.persistence.initialize-schema=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    List<String> asset = jdbc.queryForList(
                            "select id from DEDUP4J_DATABASE_CHANGELOG", String.class);
                    List<String> history = jdbc.queryForList(
                            "select id from DEDUP4J_HISTORY_DATABASE_CHANGELOG", String.class);
                    assertThat(asset).containsExactly(
                            "001-create-asset-content", "002-create-reference-repair", "004-reference-repair-append-only-h2",
                            "005-asset-content-object-deleted-at");
                    assertThat(history).isNotEmpty().doesNotContain("001-create-asset-content");
                    assertThat(jdbc.queryForObject(
                            "select count(*) from DEDUP4J_HISTORY_DATABASE_CHANGELOG_LOCK", Integer.class))
                            .isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class LowerCaseH2Configuration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:dedup4j-history-lower-" + DATABASE_SEQUENCE.incrementAndGet()
                    + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class H2Configuration {
        @Bean
        DataSource dataSource() {
            return h2();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-19T00:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AssetContentOnlyConfiguration {
        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource dataSource = h2();
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/dedup4j/db.changelog-master.yaml");
            liquibase.afterPropertiesSet();
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MySqlConfiguration {
        static final java.util.concurrent.atomic.AtomicReference<DataSource> DELEGATE =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Bean
        DataSource dataSource() {
            DataSource delegate = h2();
            DELEGATE.set(delegate);
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(delegate, args);
                            return result instanceof Connection connection ? mysqlConnection(connection) : result;
                        }
                        catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        private static Connection mysqlConnection(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("getMetaData")) {
                                DatabaseMetaData metadata = delegate.getMetaData();
                                return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                        new Class<?>[]{DatabaseMetaData.class}, (p, m, a) -> {
                                            if (m.getName().equals("getDatabaseProductName")) {
                                                return "MySQL";
                                            }
                                            try {
                                                return m.invoke(metadata, a);
                                            }
                                            catch (java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        });
                            }
                            return method.invoke(delegate, args);
                        }
                        catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static JdbcDataSource h2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dedup4j-history-config-" + DATABASE_SEQUENCE.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
