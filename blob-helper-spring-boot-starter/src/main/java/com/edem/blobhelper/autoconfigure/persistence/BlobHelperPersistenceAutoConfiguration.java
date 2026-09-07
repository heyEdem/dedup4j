package com.edem.blobhelper.autoconfigure.persistence;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.blobhelper.jpa.AssetContent;
import jakarta.persistence.EntityManagerFactory;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
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
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, SpringLiquibase.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties({BlobHelperProperties.class, LiquibaseProperties.class})
@Import(DatabaseInitializationDependencyConfigurer.class)
public class BlobHelperPersistenceAutoConfiguration {

    private static final String LIQUIBASE_BEAN = "blobHelperLiquibase";
    private static final String VALIDATOR_BEAN = "blobHelperSchemaValidator";

    @Bean(name = LIQUIBASE_BEAN)
    SpringLiquibase blobHelperLiquibase(DataSource dataSource, BlobHelperProperties properties) {
        SchemaInitialization mode = properties.getPersistence().getInitializeSchema();
        boolean shouldInitialize = mode == SchemaInitialization.ALWAYS
                || (mode == SchemaInitialization.EMBEDDED && EmbeddedDatabaseConnection.isEmbedded(dataSource));

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(BlobHelperSchemaValidator.CHANGELOG);
        liquibase.setDatabaseChangeLogTable("BLOB_HELPER_DATABASE_CHANGELOG");
        liquibase.setDatabaseChangeLogLockTable("BLOB_HELPER_DATABASE_CHANGELOG_LOCK");
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
    SchemaManagementProvider blobHelperSchemaManagementProvider(DataSource dataSource) {
        return candidate -> candidate == dataSource ? SchemaManagement.MANAGED : SchemaManagement.UNMANAGED;
    }

    @Bean(name = VALIDATOR_BEAN)
    BlobHelperSchemaValidator blobHelperSchemaValidator(DataSource dataSource,
                                                        BlobHelperProperties properties) {
        return new BlobHelperSchemaValidator(dataSource, properties);
    }

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor blobHelperSchemaOrdering() {
        return beanFactory -> {
            String[] liquibaseNames = beanFactory.getBeanNamesForType(SpringLiquibase.class, false, false);
            addDependsOn(beanFactory, VALIDATOR_BEAN, liquibaseNames);
            for (String emfName : beanFactory.getBeanNamesForType(EntityManagerFactory.class, false, false)) {
                addDependsOn(beanFactory, emfName, new String[]{VALIDATOR_BEAN});
            }
        };
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor blobHelperEntityPackageAppender() {
        return new BlobHelperEntityPackageAppender();
    }

    static final class BlobHelperEntityPackageAppender
            implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware {

        private BeanFactory beanFactory;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(
                org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
            if (registry.containsBeanDefinition(EntityScanPackages.class.getName())) {
                EntityScanPackages.register(registry, AssetContent.class.getPackageName());
                return;
            }
            List<String> packages = new ArrayList<>();
            if (beanFactory != null && AutoConfigurationPackages.has(beanFactory)) {
                packages.addAll(AutoConfigurationPackages.get(beanFactory));
            }
            packages.add(AssetContent.class.getPackageName());
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
