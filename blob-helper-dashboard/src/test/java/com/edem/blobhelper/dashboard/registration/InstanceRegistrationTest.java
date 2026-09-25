package com.edem.blobhelper.dashboard.registration;

import com.edem.blobhelper.dashboard.BlobHelperDashboardApplication;
import com.edem.dedup4j.management.DashboardRegistrationProperties;
import com.edem.dedup4j.management.InstanceRegistrationClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceRegistrationTest {
    @Test
    void registrationUpsertsByStableInstanceId() {
        InstanceRegistrationController controller = new InstanceRegistrationController();
        UUID id = UUID.randomUUID();
        controller.register(new InstanceRegistration(id, "orders", "http://127.0.0.1:8081", Instant.now()));
        controller.register(new InstanceRegistration(id, "orders-renamed", "http://127.0.0.1:8082", Instant.now()));
        assertThat(controller.instances()).hasSize(1);
        assertThat(controller.instances().getFirst().instanceName()).isEqualTo("orders-renamed");
    }

    @Test
    void registrationClientDoesNotBlockApplicationStartup() {
        InstanceRegistrationClient client = new InstanceRegistrationClient(
                new DashboardRegistrationProperties(true, "http://127.0.0.1:1", "orders", "http://127.0.0.1:8081", null));
        long startedAt = System.nanoTime();
        client.registerAsync();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertThat(elapsedMillis).isLessThan(250);
    }

    @Test
    void generatedInstanceIdIsStableForTheSameIdentity() {
        DashboardRegistrationProperties properties = new DashboardRegistrationProperties(
                true, "http://127.0.0.1:9090", "orders", "http://127.0.0.1:8081", null);
        InstanceRegistrationClient first = new InstanceRegistrationClient(properties);
        InstanceRegistrationClient second = new InstanceRegistrationClient(properties);

        assertThat(first.stableInstanceId()).isEqualTo(second.stableInstanceId());
    }

    @Test
    void dashboardBindsToLoopbackByDefault() {
        assertThat(BlobHelperDashboardApplication.DEFAULT_ADDRESS).isEqualTo("127.0.0.1");
        assertThat(BlobHelperDashboardApplication.DEFAULT_PORT).isEqualTo(9090);
    }
}
