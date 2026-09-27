package com.edem.dedup4j.dashboard;

import com.edem.dedup4j.dashboard.api.DashboardController;
import com.edem.dedup4j.dashboard.persistence.DashboardDatabase;
import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import com.edem.dedup4j.dashboard.polling.DashboardDatabaseProperties;
import com.edem.dedup4j.dashboard.polling.InstancePollingService;
import com.edem.dedup4j.dashboard.registration.InstanceRegistration;
import com.edem.dedup4j.dashboard.registration.InstanceRegistrationController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class MultiInstanceDashboardIntegrationTest {
    @TempDir Path tempDir;

    private HttpServer managementServer;
    private InstanceRepository instances;
    private FailureEventRepository failures;
    private InstancePollingService polling;
    private DashboardController dashboard;
    private UUID ordersId;
    private UUID billingId;
    private final AtomicBoolean billingAvailable = new AtomicBoolean(true);
    private volatile String ordersMetrics = metrics(10, 4, 4, 1_000, 400, 600, 2);
    private volatile String billingMetrics = metrics(5, 1, 1, 500, 100, 400, 1);

    @BeforeEach
    void setUp() throws Exception {
        managementServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        managementServer.createContext("/orders/v1/metrics", exchange -> respond(exchange, ordersMetrics));
        managementServer.createContext("/billing/v1/metrics", exchange -> {
            if (!billingAvailable.get()) {
                exchange.close();
                return;
            }
            respond(exchange, billingMetrics);
        });
        managementServer.start();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("dashboard.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        instances = new InstanceRepository(jdbc);
        MetricSnapshotRepository snapshots = new MetricSnapshotRepository(jdbc);
        failures = new FailureEventRepository(jdbc);
        polling = new InstancePollingService(instances, snapshots, failures,
                new DashboardDatabaseProperties(null, Duration.ofSeconds(30), Duration.ofDays(7)));
        dashboard = new DashboardController(instances, snapshots, failures);

        ordersId = UUID.randomUUID();
        billingId = UUID.randomUUID();
        int port = managementServer.getAddress().getPort();
        InstanceRegistrationController registration = new InstanceRegistrationController(instances);
        registration.register(new InstanceRegistration(ordersId, "orders", url(port, "orders"), Instant.now()));
        registration.register(new InstanceRegistration(billingId, "billing", url(port, "billing"), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        if (managementServer != null) managementServer.stop(0);
    }

    @Test
    void twoInstancesRegisterAndContributeIndependentMetrics() {
        polling.pollAll();
        ordersMetrics = metrics(13, 5, 5, 1_300, 500, 800, 3);
        billingMetrics = metrics(7, 2, 2, 700, 200, 500, 2);
        polling.pollAll();

        var overview = dashboard.overview();
        assertThat(overview.instanceCount()).isEqualTo(2);
        assertThat(overview.healthyInstanceCount()).isEqualTo(2);
        assertThat(overview.uploads()).isEqualTo(20);
        assertThat(overview.logicalBytes()).isEqualTo(2_000);
        assertThat(overview.physicalBytes()).isEqualTo(1_300);
        assertThat(overview.avoidedBytes()).isEqualTo(700);
        assertThat(dashboard.history(ordersId).points()).hasSize(2);
        assertThat(dashboard.history(billingId).points()).hasSize(2);
    }

    @Test
    void failedInstanceIsMarkedDisconnectedWithoutHidingHealthyInstance() {
        polling.pollAll();
        billingAvailable.set(false);
        polling.pollAll();

        var status = dashboard.instanceStatus();
        assertThat(status).anySatisfy(instance -> {
            if (instance.instanceId().equals(billingId)) assertThat(instance.status()).isEqualTo("DISCONNECTED");
        });
        assertThat(status).anySatisfy(instance -> {
            if (instance.instanceId().equals(ordersId)) assertThat(instance.status()).isEqualTo("HEALTHY");
        });
        assertThat(dashboard.failures(null)).hasSize(1);
    }

    @Test
    void failureDetailsExpireAfterSevenDaysWhileSnapshotsRemain() {
        Instant now = Instant.now();
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), ordersId,
                now.minus(Duration.ofDays(8)), "poll", "old"));
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), ordersId,
                now.minus(Duration.ofDays(1)), "poll", "recent"));
        polling.pollAll();

        assertThat(dashboard.failures(null)).extracting("message").containsExactly("recent");
        assertThat(dashboard.history(ordersId).points()).hasSize(1);
    }

    private String url(int port, String instance) {
        return "http://127.0.0.1:" + port + "/" + instance + "/v1";
    }

    private static String metrics(long uploads, long duplicates, long skipped, long accepted,
                                   long avoided, long physical, long contentCount) {
        return "{\"uploads\":" + uploads + ",\"duplicates\":" + duplicates
                + ",\"skippedPhysicalWrites\":" + skipped + ",\"acceptedBytes\":" + accepted
                + ",\"avoidedBytes\":" + avoided + ",\"contentCount\":" + contentCount
                + ",\"physicalBytes\":" + physical + "}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
