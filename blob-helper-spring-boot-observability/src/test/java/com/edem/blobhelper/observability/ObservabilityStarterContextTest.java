package com.edem.dedup4j.observability;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.blobhelper.dashboard.api.EmbeddedDashboardController;
import com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardAutoConfiguration;
import com.edem.dedup4j.management.Dedup4jManagementAutoConfiguration;
import com.edem.dedup4j.management.Dedup4jManagementController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityStarterContextTest {

    private final WebApplicationContextRunner servletContext = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    Dedup4jManagementAutoConfiguration.class,
                    BlobHelperDashboardAutoConfiguration.class))
            .withUserConfiguration(RequiredProperties.class);

    private final WebApplicationContextRunner managementEnabledServletContext = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    Dedup4jManagementAutoConfiguration.class,
                    BlobHelperDashboardAutoConfiguration.class));

    @Test
    void dashboardIsEnabledByDefault() {
        servletContext.run(context -> assertThat(context)
                .hasSingleBean(EmbeddedDashboardController.class));
    }

    @Test
    void managementRemainsDisabledByDefault() {
        servletContext.run(context -> assertThat(context)
                .doesNotHaveBean(Dedup4jManagementController.class));
    }

    @Test
    void enablesManagementExplicitly() {
        managementEnabledServletContext.withPropertyValues("dedup4j.management.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(EmbeddedDashboardController.class)
                        .hasSingleBean(Dedup4jManagementController.class));
    }

    @Test
    void canDisableBoth() {
        servletContext.withPropertyValues(
                        "blob-helper.dashboard.enabled=false",
                        "dedup4j.management.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(EmbeddedDashboardController.class)
                        .doesNotHaveBean(Dedup4jManagementController.class));
    }

    @Test
    void routesAreReadOnly() {
        managementEnabledServletContext.withPropertyValues("dedup4j.management.enabled=true")
                .run(context -> {
                    var mapping = context.getBean(RequestMappingHandlerMapping.class);
                    var observabilityHandlers = mapping.getHandlerMethods().entrySet().stream()
                            .filter(entry -> isObservabilityController(entry.getValue()))
                            .toList();

                    assertThat(observabilityHandlers).isNotEmpty();
                    assertThat(observabilityHandlers)
                            .allSatisfy(entry -> assertThat(entry.getKey().getMethodsCondition().getMethods())
                                    .isNotEmpty()
                                    .allMatch(method -> method == RequestMethod.GET || method == RequestMethod.HEAD));
                });
    }

    @Test
    void dashboardIsNotCreatedOutsideServletApplicationContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BlobHelperDashboardAutoConfiguration.class))
                .withUserConfiguration(RequiredProperties.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(EmbeddedDashboardController.class));
    }

    private static boolean isObservabilityController(HandlerMethod handlerMethod) {
        Class<?> beanType = handlerMethod.getBeanType();
        return beanType == EmbeddedDashboardController.class
                || beanType == Dedup4jManagementController.class;
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredProperties {
        @Bean
        Dedup4jProperties dedup4jProperties() {
            return new Dedup4jProperties();
        }
    }
}
