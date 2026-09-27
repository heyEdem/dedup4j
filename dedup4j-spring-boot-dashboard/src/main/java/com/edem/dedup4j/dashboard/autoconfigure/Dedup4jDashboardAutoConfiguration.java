package com.edem.dedup4j.dashboard.autoconfigure;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.dashboard.api.EmbeddedDashboardController;
import com.edem.dedup4j.dashboard.api.EmbeddedDashboardSnapshotService;
import com.edem.dedup4j.dashboard.api.EmbeddedDashboardView;
import com.edem.dedup4j.management.Dedup4jManagementProperties;
import com.edem.dedup4j.management.Dedup4jManagementSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({RestController.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "dedup4j.dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({Dedup4jDashboardProperties.class, Dedup4jManagementProperties.class})
public class Dedup4jDashboardAutoConfiguration {
    @Bean
    EmbeddedDashboardSnapshotService embeddedDashboardSnapshotService(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<com.edem.dedup4j.jpa.AssetContentRepository> contentRepository,
            Dedup4jProperties dedup4jProperties,
            Dedup4jManagementProperties managementProperties) {
        return new EmbeddedDashboardSnapshotService(meterRegistry, contentRepository, dedup4jProperties, managementProperties);
    }

    @Bean
    EmbeddedDashboardController embeddedDashboardController(
            EmbeddedDashboardSnapshotService snapshots,
            Dedup4jDashboardProperties properties,
            ObjectProvider<Dedup4jManagementSnapshot.FailureSource> failureSource) {
        return new EmbeddedDashboardController(snapshots, properties, failureSource.getIfAvailable(() -> since -> java.util.List.of()));
    }

    @Bean
    WebMvcConfigurer embeddedDashboardResources(Dedup4jDashboardProperties properties) {
        return EmbeddedDashboardController.resourceConfiguration(properties);
    }
}
