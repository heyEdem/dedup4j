package com.edem.blobhelper.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementDashboardContractTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BlobHelperManagementAutoConfiguration.class))
            .withPropertyValues("blob-helper.management.enabled=true", "dedup4j.storage.provider=local",
                    "blob-helper.management.instance-id=11111111-1111-1111-1111-111111111111",
                    "blob-helper.management.instance-name=orders");

    @Test
    void exposesAllProviderNeutralDashboardResponseShapes() {
        contextRunner.run(context -> {
            var controller = context.getBean(BlobHelperManagementController.class);
            var info = controller.info();
            var health = controller.health();
            var metrics = controller.metrics();
            var failures = controller.failures(Instant.EPOCH);

            assertThat(info.instanceName()).isEqualTo("orders");
            assertThat(info.provider()).isEqualTo("local");
            assertThat(health.status()).isEqualTo("UP");
            assertThat(health.observedAt()).isNotNull();
            assertThat(metrics).extracting(BlobHelperManagementSnapshot.Metrics::uploads,
                    BlobHelperManagementSnapshot.Metrics::acceptedBytes,
                    BlobHelperManagementSnapshot.Metrics::contentCount)
                    .containsExactly(0L, 0L, 0L);
            assertThat(failures).isEqualTo(List.of());
        });
    }
}
