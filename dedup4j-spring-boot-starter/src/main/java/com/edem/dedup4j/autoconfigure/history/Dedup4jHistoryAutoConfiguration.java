package com.edem.dedup4j.autoconfigure.history;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.autoconfigure.persistence.Dedup4jPersistenceAutoConfiguration;
import com.edem.dedup4j.autoconfigure.persistence.SchemaInitialization;
import com.edem.dedup4j.history.DefaultInstanceId;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.JpaUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.FailureLogRepository;
import com.edem.dedup4j.historystore.UploadLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

@AutoConfiguration(after = {Dedup4jPersistenceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ConditionalOnClass({EntityManagerFactory.class, SpringLiquibase.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(Dedup4jProperties.class)
public class Dedup4jHistoryAutoConfiguration {

    public static final String HISTORY_CHANGELOG = "classpath:db/dedup4j/history/db.changelog-history.yaml";

    private static final String ASSET_LIQUIBASE_BEAN = "dedup4jLiquibase";
    private static final String GUARD_BEAN = "dedup4jHistoryDatabaseGuard";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "dedup4j.history.enabled", matchIfMissing = true)
    static class EnabledHistoryConfiguration {

        @Bean(name = GUARD_BEAN)
        HistoryDatabaseGuard dedup4jHistoryDatabaseGuard(DataSource dataSource) {
            return new HistoryDatabaseGuard(dataSource);
        }

        @Bean(name = "dedup4jHistoryLiquibase")
        @DependsOn({ASSET_LIQUIBASE_BEAN, GUARD_BEAN})
        SpringLiquibase dedup4jHistoryLiquibase(DataSource dataSource, Dedup4jProperties properties) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog(HISTORY_CHANGELOG);
            liquibase.setDatabaseChangeLogTable("DEDUP4J_DATABASE_CHANGELOG");
            liquibase.setDatabaseChangeLogLockTable("DEDUP4J_DATABASE_CHANGELOG_LOCK");
            liquibase.setShouldRun(properties.getPersistence().getInitializeSchema().shouldInitialize(dataSource));
            return liquibase;
        }

        @Bean
        @ConditionalOnMissingBean(UploadHistory.class)
        @DependsOn(GUARD_BEAN)
        UploadHistory uploadHistory(
                ObjectProvider<EntityManager> entityManagers,
                ObjectProvider<EntityManagerFactory> entityManagerFactories,
                PlatformTransactionManager transactionManager,
                ObjectProvider<Clock> clock,
                @Value("${dedup4j.history.instance-id:${dedup4j.management.instance-id:}}") String instanceId) {
            EntityManager entityManager = entityManagers.getIfAvailable();
            if (entityManager == null) {
                entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                        entityManagerFactories.getObject());
            }
            return new JpaUploadHistory(
                    new UploadLedgerRepository(entityManager),
                    new FailureLogRepository(entityManager),
                    instanceId == null || instanceId.isBlank() ? DefaultInstanceId.value() : instanceId,
                    transactionManager,
                    clock.getIfAvailable(Clock::systemUTC));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "dedup4j.history.enabled", havingValue = false)
    static class DisabledHistoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(UploadHistory.class)
        UploadHistory uploadHistory(ObjectProvider<MeterRegistry> meterRegistry) {
            return new DisabledUploadHistory(meterRegistry);
        }
    }
}
