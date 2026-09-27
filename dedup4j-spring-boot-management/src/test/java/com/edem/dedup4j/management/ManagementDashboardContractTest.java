package com.edem.dedup4j.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementDashboardContractTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Dedup4jManagementAutoConfiguration.class))
            .withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local",
                    "dedup4j.management.instance-id=11111111-1111-1111-1111-111111111111",
                    "dedup4j.management.instance-name=orders");

    @Test
    void exposesAllProviderNeutralDashboardResponseShapes() {
        contextRunner.run(context -> {
            var controller = context.getBean(Dedup4jManagementController.class);
            var info = controller.info();
            var health = controller.health();
            var metrics = controller.metrics();
            var failures = controller.failures(Instant.EPOCH);

            assertThat(info.instanceName()).isEqualTo("orders");
            assertThat(info.provider()).isEqualTo("local");
            assertThat(health.status()).isEqualTo("UP");
            assertThat(health.observedAt()).isNotNull();
            assertThat(metrics).extracting(Dedup4jManagementSnapshot.Metrics::uploads,
                    Dedup4jManagementSnapshot.Metrics::acceptedBytes,
                    Dedup4jManagementSnapshot.Metrics::contentCount)
                    .containsExactly(0L, 0L, 0L);
            assertThat(failures).isEqualTo(List.of());
        });
    }
}
