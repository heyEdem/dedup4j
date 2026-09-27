package com.edem.dedup4j.management;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.edem.dedup4j.autoconfigure.Dedup4jProperties;

@AutoConfiguration
@EnableConfigurationProperties({
        Dedup4jManagementProperties.class,
        Dedup4jProperties.class,
        DashboardRegistrationProperties.class
})
@ConditionalOnProperty(prefix = "dedup4j.management", name = "enabled", havingValue = "true")
public class Dedup4jManagementAutoConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "dedup4j.dashboard-registration", name = "enabled", havingValue = "true")
    InstanceRegistrationClient instanceRegistrationClient(DashboardRegistrationProperties properties) {
        return new InstanceRegistrationClient(properties);
    }

    @Bean
    Dedup4jManagementController dedup4jManagementController(
            Dedup4jManagementProperties managementProperties,
            Dedup4jProperties dedup4jProperties,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            org.springframework.beans.factory.ObjectProvider<com.edem.dedup4j.jpa.AssetContentRepository> contentRepository,
            org.springframework.beans.factory.ObjectProvider<Dedup4jManagementSnapshot.FailureSource> failureSource
    ) {
        return new Dedup4jManagementController(
                managementProperties, dedup4jProperties, meterRegistry, contentRepository, failureSource);
    }
}
