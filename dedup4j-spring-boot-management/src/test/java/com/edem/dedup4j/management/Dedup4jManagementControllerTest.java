package com.edem.dedup4j.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jManagementControllerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Dedup4jManagementAutoConfiguration.class));

    @Test
    void managementIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(Dedup4jManagementController.class));
    }

    @Test
    void managementIsCreatedWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues(
                        "dedup4j.management.enabled=true",
                        "dedup4j.storage.provider=local")
                .run(context -> assertThat(context).hasSingleBean(Dedup4jManagementController.class));
    }

    @Test
    void metricsResponseContainsProviderNeutralCounters() {
        Dedup4jManagementSnapshot.Metrics metrics = new Dedup4jManagementSnapshot.Metrics(4, 2, 2, 100, 50, 3, 75);
        assertThat(metrics.uploads()).isEqualTo(4);
        assertThat(metrics.acceptedBytes()).isEqualTo(100);
        assertThat(metrics.avoidedBytes()).isEqualTo(50);
        assertThat(Arrays.stream(metrics.getClass().getRecordComponents()).map(component -> component.getType().getName()))
                .allMatch(type -> !type.contains("software.amazon") && !type.contains("com.azure"));
    }

    @Test
    void managementEndpointsAreReadOnly() {
        assertThat(Arrays.stream(Dedup4jManagementController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RequestMapping.class))
                .map(method -> method.getAnnotation(RequestMapping.class))
                .flatMap(mapping -> Arrays.stream(mapping.method())))
                .allMatch(method -> method == RequestMethod.GET);
    }
}
