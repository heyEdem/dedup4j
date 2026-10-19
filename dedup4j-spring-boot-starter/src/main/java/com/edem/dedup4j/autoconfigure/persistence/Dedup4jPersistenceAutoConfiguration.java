package com.edem.dedup4j.autoconfigure.persistence;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.historystore.UploadEvent;
import jakarta.persistence.EntityManagerFactory;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.SchemaManagement;
import org.springframework.boot.jdbc.SchemaManagementProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, SpringLiquibase.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties({Dedup4jProperties.class, LiquibaseProperties.class})
@Import(DatabaseInitializationDependencyConfigurer.class)
public class Dedup4jPersistenceAutoConfiguration {

    private static final String LIQUIBASE_BEAN = "dedup4jLiquibase";
    private static final String HISTORY_LIQUIBASE_BEAN = "dedup4jHistoryLiquibase";
    private static final String HISTORY_GUARD_BEAN = "dedup4jHistoryDatabaseGuard";
    private static final String VALIDATOR_BEAN = "dedup4jSchemaValidator";

    @Bean(name = LIQUIBASE_BEAN)
    SpringLiquibase dedup4jLiquibase(DataSource dataSource, Dedup4jProperties properties) {
        SchemaInitialization mode = properties.getPersistence().getInitializeSchema();
        boolean shouldInitialize = mode.shouldInitialize(dataSource);

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(Dedup4jSchemaValidator.CHANGELOG);
        liquibase.setDatabaseChangeLogTable("DEDUP4J_DATABASE_CHANGELOG");
        liquibase.setDatabaseChangeLogLockTable("DEDUP4J_DATABASE_CHANGELOG_LOCK");
        liquibase.setShouldRun(shouldInitialize);
        return liquibase;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", matchIfMissing = true)
    @Conditional(ConsumerChangelogCondition.class)
    static class ConsumerLiquibaseConfiguration {

        @Bean(name = "liquibase")
        SpringLiquibase consumerLiquibase(DataSource dataSource, LiquibaseProperties properties) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog(properties.getChangeLog());
            liquibase.setClearCheckSums(properties.isClearChecksums());
            liquibase.setDropFirst(properties.isDropFirst());
            liquibase.setShouldRun(properties.isEnabled());
            liquibase.setChangeLogParameters(properties.getParameters());
            liquibase.setTestRollbackOnUpdate(properties.isTestRollbackOnUpdate());
            liquibase.setTag(properties.getTag());
            liquibase.setDefaultSchema(properties.getDefaultSchema());
            liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
            liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
            liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
            if (properties.getContexts() != null) {
                liquibase.setContexts(String.join(",", properties.getContexts()));
            }
            if (properties.getLabelFilter() != null) {
                liquibase.setLabels(String.join(",", properties.getLabelFilter()));
            }
            return liquibase;
        }
    }

    static final class ConsumerChangelogCondition extends AnyNestedCondition {
        ConsumerChangelogCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.liquibase.change-log")
        static class ExplicitChangelog {
        }

        @ConditionalOnResource(resources = "classpath:/db/changelog/db.changelog-master.yaml")
        static class ConventionalChangelog {
        }
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    SchemaManagementProvider dedup4jSchemaManagementProvider(DataSource dataSource) {
        return candidate -> candidate == dataSource ? SchemaManagement.MANAGED : SchemaManagement.UNMANAGED;
    }

    @Bean(name = VALIDATOR_BEAN)
    Dedup4jSchemaValidator dedup4jSchemaValidator(DataSource dataSource,
                                                        Dedup4jProperties properties,
                                                        ListableBeanFactory beanFactory) {
        // The history tables are probed only when the history auto-configuration is in the context.
        boolean historyActive = beanFactory.containsBeanDefinition(HISTORY_LIQUIBASE_BEAN);
        return new Dedup4jSchemaValidator(dataSource, properties, historyActive);
    }

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor dedup4jSchemaOrdering() {
        return beanFactory -> {
            String[] liquibaseNames = beanFactory.getBeanNamesForType(SpringLiquibase.class, false, false);
            addDependsOn(beanFactory, VALIDATOR_BEAN, liquibaseNames);
            // Nothing is migrated before the history guard has accepted the database.
            if (beanFactory.containsBeanDefinition(HISTORY_GUARD_BEAN)) {
                addDependsOn(beanFactory, LIQUIBASE_BEAN, new String[]{HISTORY_GUARD_BEAN});
            }
            for (String emfName : beanFactory.getBeanNamesForType(EntityManagerFactory.class, false, false)) {
                addDependsOn(beanFactory, emfName, new String[]{VALIDATOR_BEAN});
            }
        };
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor dedup4jEntityPackageAppender() {
        return new Dedup4jEntityPackageAppender();
    }

    static final class Dedup4jEntityPackageAppender
            implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, EnvironmentAware {

        private BeanFactory beanFactory;
        private boolean historyEnabled = true;

        @Override
        public void setEnvironment(Environment environment) {
            historyEnabled = environment.getProperty("dedup4j.history.enabled", Boolean.class, true);
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(
                org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
            List<String> own = new ArrayList<>();
            own.add(AssetContent.class.getPackageName());
            if (historyEnabled) {
                own.add(UploadEvent.class.getPackageName());
            }
            if (registry.containsBeanDefinition(EntityScanPackages.class.getName())) {
                EntityScanPackages.register(registry, own);
                return;
            }
            List<String> packages = new ArrayList<>();
            if (beanFactory != null && AutoConfigurationPackages.has(beanFactory)) {
                packages.addAll(AutoConfigurationPackages.get(beanFactory));
            }
            packages.addAll(own);
            EntityScanPackages.register(registry, packages);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }

    private static void addDependsOn(ConfigurableListableBeanFactory beanFactory,
                                     String beanName, String[] dependencies) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        List<String> merged = new ArrayList<>();
        if (definition.getDependsOn() != null) {
            merged.addAll(List.of(definition.getDependsOn()));
        }
        for (String dependency : dependencies) {
            if (!merged.contains(dependency) && !dependency.equals(beanName)) {
                merged.add(dependency);
            }
        }
        definition.setDependsOn(merged.toArray(String[]::new));
    }

}
