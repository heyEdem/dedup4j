package com.edem.dedup4j.autoconfigure.persistence;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.jpa.AssetContent;
import example.defaultpkg.DefaultEntity;
import example.explicit.ExplicitEntity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Dedup4jPersistenceAutoConfigurationTest {

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestDataSourceConfiguration.class, Dedup4jPersistenceAutoConfiguration.class)
            .withPropertyValues("spring.liquibase.enabled=false");

    @Test
    void embeddedInitializesSchemaEvenWhenBootLiquibaseIsDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(tableExists(context.getBean(DataSource.class))).isTrue();
        });
    }

    @Test
    void alwaysInitializesWrappedExternalDataSource() {
        new ApplicationContextRunner()
                .withUserConfiguration(WrappedExternalDataSourceConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.enabled=false",
                        "dedup4j.persistence.initialize-schema=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableExists(context.getBean(DataSource.class))).isTrue();
                });
    }

    @Test
    void externalEmbeddedModeDoesNotMutateAndReportsMigrationGuidance() {
        DataSource dataSource = new ExternalMetadataDataSource(
                Dedup4jPersistenceAutoConfigurationTest.dataSource());
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withUserConfiguration(
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.enabled=false",
                        "dedup4j.persistence.initialize-schema=embedded")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("blob_helper_asset_content")
                        .hasMessageContaining("embedded")
                        .hasMessageContaining("classpath:db/blob-helper/db.changelog-master.yaml"));
        assertBlobSchemaAbsent(dataSource);
    }

    @Test
    void neverDoesNotMutateAndReportsMigrationGuidance() {
        DataSource dataSource = dataSource();
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withUserConfiguration(
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.enabled=false",
                        "dedup4j.persistence.initialize-schema=never")
                .run(context -> {
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasMessageContaining("blob_helper_asset_content")
                            .hasMessageContaining("never")
                            .hasMessageContaining("classpath:db/blob-helper/db.changelog-master.yaml");
                });
        assertBlobSchemaAbsent(dataSource);
    }

    @Test
    void neverAcceptsAnApplicationManagedSchema() {
        new ApplicationContextRunner()
                .withUserConfiguration(PrecreatedSchemaConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.enabled=false",
                        "dedup4j.persistence.initialize-schema=never")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void neverRejectsAnApplicationSchemaWithMissingColumns() {
        new ApplicationContextRunner()
                .withUserConfiguration(IncompleteSchemaConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.enabled=false",
                        "dedup4j.persistence.initialize-schema=never")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("blob_helper_asset_content")
                        .hasMessageContaining("classpath:db/blob-helper/db.changelog-master.yaml"));
    }

    @Test
    void applicationLiquibaseBeanCoexistsWithBlobInitializer() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConsumerLiquibaseConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues("spring.liquibase.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(liquibase.integration.spring.SpringLiquibase.class))
                            .containsKeys("dedup4jLiquibase", "consumerLiquibase");
                });
    }

    @Test
    void explicitlyConfiguredConsumerChangelogStillExecutes() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDataSourceConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withPropertyValues(
                        "spring.liquibase.change-log=classpath:db/blob-helper/consumer-test-changelog.yaml",
                        "spring.liquibase.parameters.marker=configured")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableExistsNamed(context.getBean(DataSource.class), "CONSUMER_CONFIGURED_MARKER"))
                            .isTrue();
                });
    }

    @Test
    void conventionalConsumerChangelogIsDetectedAndExecuted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDataSourceConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .withClassLoader(conventionalChangelogClassLoader())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableExistsNamed(context.getBean(DataSource.class), "CONSUMER_DEFAULT_MARKER"))
                            .isTrue();
                });
    }

    @Test
    void blankConsumerContextStartsWithBlobMigrationOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(tableExists(context.getBean(DataSource.class))).isTrue();
            assertThat(tableExistsNamed(context.getBean(DataSource.class), "CONSUMER_DEFAULT_MARKER"))
                    .isFalse();
        });
    }

    private static ClassLoader conventionalChangelogClassLoader() {
        try {
            URL isolatedResources = Path.of("src/test/resources/consumer-default-changelog")
                    .toAbsolutePath().toUri().toURL();
            return new URLClassLoader(new URL[]{isolatedResources},
                    Dedup4jPersistenceAutoConfigurationTest.class.getClassLoader());
        }
        catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to expose isolated conventional changelog fixture", exception);
        }
    }

    @Test
    void explicitEntityScanPackagesRemainAndBlobPackageIsAdded() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDataSourceConfiguration.class, ExplicitEntityScanConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .run(context -> assertThat(org.springframework.boot.persistence.autoconfigure.EntityScanPackages
                        .get(context).getPackageNames())
                        .contains(ExplicitEntity.class.getPackageName(), AssetContent.class.getPackageName()));
    }

    @Test
    void defaultAutoConfigurationPackagesRemainAndBlobPackageIsAdded() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDataSourceConfiguration.class, DefaultEntityPackageConfiguration.class,
                        Dedup4jPersistenceAutoConfiguration.class)
                .run(context -> assertThat(org.springframework.boot.persistence.autoconfigure.EntityScanPackages
                        .get(context).getPackageNames())
                        .contains(DefaultEntity.class.getPackageName(), AssetContent.class.getPackageName()));
    }

    @Test
    void packagedChangelogCreatesSchemaWithHibernateCompatibleColumns() throws Exception {
        JdbcDataSource dataSource = dataSource();
        liquibase.integration.spring.SpringLiquibase liquibase = new liquibase.integration.spring.SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/blob-helper/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        try (Connection connection = dataSource.getConnection()) {
            Set<String> columns = new HashSet<>();
            try (java.sql.ResultSet result = connection.getMetaData().getColumns(null, null,
                    "BLOB_HELPER_ASSET_CONTENT", null)) {
                while (result.next()) {
                    columns.add(result.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns).containsExactlyInAnyOrder(
                    "id", "hash_algorithm", "content_hash", "size_bytes", "object_key",
                    "storage_provider", "bucket_or_container", "content_type", "original_extension",
                    "ref_count", "created_at", "updated_at", "version");
        }

        LocalContainerEntityManagerFactoryBean entityManagerFactory =
                new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan(AssetContent.class.getPackageName());
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        entityManagerFactory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        entityManagerFactory.afterPropertiesSet();
        entityManagerFactory.destroy();
    }

    private static boolean tableExists(DataSource dataSource) {
        return Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.tables where table_name = 'BLOB_HELPER_ASSET_CONTENT'",
                Integer.class) > 0);
    }

    private static boolean tableExistsNamed(DataSource dataSource, String tableName) {
        return Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class, tableName) > 0);
    }

    private static void assertBlobSchemaAbsent(DataSource dataSource) {
        assertThat(tableExistsNamed(dataSource, "BLOB_HELPER_ASSET_CONTENT")).isFalse();
        assertThat(tableExistsNamed(dataSource, "BLOB_HELPER_DATABASE_CHANGELOG")).isFalse();
        assertThat(tableExistsNamed(dataSource, "BLOB_HELPER_DATABASE_CHANGELOG_LOCK")).isFalse();
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dedup4j-schema-" + DATABASE_SEQUENCE.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return Dedup4jPersistenceAutoConfigurationTest.dataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestDataSourceConfiguration.class)
    static class PrecreatedSchemaConfiguration {
        @Bean
        Object precreateSchema(DataSource dataSource) {
            new JdbcTemplate(dataSource).execute("create table blob_helper_asset_content ("
                    + "id uuid not null, hash_algorithm varchar(32) not null, content_hash varchar(128) not null,"
                    + "size_bytes bigint not null, object_key varchar(1024) not null, storage_provider varchar(64) not null,"
                    + "bucket_or_container varchar(255) not null, content_type varchar(255), original_extension varchar(32),"
                    + "ref_count bigint not null, created_at timestamp not null, updated_at timestamp not null, version bigint not null,"
                    + "constraint uk_blob_helper_asset_content_identity unique (hash_algorithm, content_hash, size_bytes))");
            return new Object();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestDataSourceConfiguration.class)
    static class IncompleteSchemaConfiguration {
        @Bean
        Object precreateIncompleteSchema(DataSource dataSource) {
            new JdbcTemplate(dataSource).execute(
                    "create table blob_helper_asset_content (id uuid not null primary key)");
            return new Object();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestDataSourceConfiguration.class)
    static class ConsumerLiquibaseConfiguration {
        @Bean(name = "consumerLiquibase")
        liquibase.integration.spring.SpringLiquibase consumerLiquibase(DataSource dataSource) {
            liquibase.integration.spring.SpringLiquibase liquibase = new liquibase.integration.spring.SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setShouldRun(false);
            return liquibase;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ExplicitEntity.class)
    static class ExplicitEntityScanConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = DefaultEntity.class)
    static class DefaultEntityPackageConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class WrappedExternalDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return new ExternalMetadataDataSource(Dedup4jPersistenceAutoConfigurationTest.dataSource());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    static final class ExternalMetadataDataSource implements DataSource {
        private final DataSource delegate;

        ExternalMetadataDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override public Connection getConnection() throws SQLException {
            return ExternalMetadataConnection.proxy(delegate.getConnection());
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return ExternalMetadataConnection.proxy(delegate.getConnection(username, password));
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
    }

    static final class ExternalMetadataConnection implements java.lang.reflect.InvocationHandler {
        private final Connection delegate;
        ExternalMetadataConnection(Connection delegate) { this.delegate = delegate; }

        static Connection proxy(Connection delegate) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Dedup4jPersistenceAutoConfigurationTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, new ExternalMetadataConnection(delegate));
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (method.getName().equals("getMetaData")) {
                DatabaseMetaData metadata = delegate.getMetaData();
                return java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (metadataProxy, metadataMethod, metadataArgs) -> metadataMethod.getName().equals("getURL")
                                ? "jdbc:postgresql://localhost/blob"
                                : metadataMethod.invoke(metadata, metadataArgs));
            }
            try {
                return method.invoke(delegate, args);
            }
            catch (java.lang.reflect.InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
