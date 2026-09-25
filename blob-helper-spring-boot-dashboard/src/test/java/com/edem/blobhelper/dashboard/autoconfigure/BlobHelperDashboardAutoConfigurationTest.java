package com.edem.blobhelper.dashboard.autoconfigure;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.blobhelper.dashboard.api.EmbeddedDashboardController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BlobHelperDashboardAutoConfigurationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BlobHelperDashboardAutoConfiguration.class))
            .withUserConfiguration(RequiredProperties.class);

    @Test
    void isEnabledByDefaultInServletWebApplications() {
        context.run(c -> assertThat(c).hasSingleBean(EmbeddedDashboardController.class));
    }

    @Test
    void canBeDisabled() {
        context.withPropertyValues("blob-helper.dashboard.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(EmbeddedDashboardController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredProperties {
        @Bean Dedup4jProperties dedup4jProperties() { return new Dedup4jProperties(); }
    }
}
