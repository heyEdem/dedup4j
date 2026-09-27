package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.DashboardDatabase;
import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.RequestParam;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardControllerTest {
    @TempDir java.nio.file.Path tempDir;
    private DashboardController controller;
    private UUID instance;
    private final Instant now = Instant.parse("2026-08-31T00:00:00Z");

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("dashboard.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        instance = UUID.randomUUID();
        new InstanceRepository(jdbc).save(instance, "orders", "http://127.0.0.1:8081", now);
        MetricSnapshotRepository snapshots = new MetricSnapshotRepository(jdbc);
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, now, 1_000, 600, 400, 10, 4, 4, 1));
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, now.plusSeconds(30), 500, 300, 200, 5, 2, 2, 1));
        FailureEventRepository failures = new FailureEventRepository(jdbc);
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance, now.minusSeconds(7 * 24 * 3600), "poll", "boundary"));
        controller = new DashboardController(new InstanceRepository(jdbc), snapshots, failures);
    }

    @Test
    void historyEndpointBindsInstanceIdThroughHttp() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/instances/{id}/history", instance))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        var json = new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(json.get("instanceId").asText()).isEqualTo(instance.toString());
        assertThat(json.get("points").size()).isEqualTo(2);
    }

    @Test
    void overviewAggregatesStoredIntervals() {
        var overview = controller.overview();
        assertThat(overview.instanceCount()).isEqualTo(1);
        assertThat(overview.uploads()).isEqualTo(15);
        assertThat(overview.logicalBytes()).isEqualTo(1_500);
        assertThat(overview.avoidedBytes()).isEqualTo(600);
        assertThat(overview.newUploads()).isEqualTo(9);
        assertThat(overview.contentCount()).isEqualTo(1);
        assertThat(overview.duplicateRate()).isEqualTo(6d / 15d);
    }

    @Test
    void failuresEndpointReturnsSevenDayWindow() {
        assertThat(controller.failures(now.minusSeconds(7 * 24 * 3600))).hasSize(1);
        assertThat(controller.failures(now.minusSeconds(7 * 24 * 3600).plusMillis(1))).isEmpty();
    }

    @Test
    void failuresEndpointNamesOptionalSinceParameterForSpringMvc() throws Exception {
        var method = DashboardController.class.getDeclaredMethod("failures", Instant.class);
        var parameter = method.getParameters()[0].getAnnotation(RequestParam.class);

        assertThat(parameter.name()).isEqualTo("since");
    }

    @Test
    void instanceStatusIncludesAggregatedObservabilityMetrics() {
        var view = controller.instanceStatus().getFirst();

        assertThat(view.uploads()).isEqualTo(15);
        assertThat(view.newUploads()).isEqualTo(9);
        assertThat(view.duplicates()).isEqualTo(6);
        assertThat(view.contentCount()).isEqualTo(1);
        assertThat(view.physicalBytes()).isEqualTo(900);
        assertThat(view.avoidedBytes()).isEqualTo(600);
    }

    @Test
    void staticDashboardIsServedFromResources() throws Exception {
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/static/index.html"))).isTrue();
    }
}
