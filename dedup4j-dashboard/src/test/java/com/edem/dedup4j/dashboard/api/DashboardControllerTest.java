package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.DashboardDatabase;
import com.edem.dedup4j.dashboard.persistence.DatasetTotalsRepository;
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
    private InstanceRepository instances;
    private DatasetTotalsRepository totals;
    private final Instant now = Instant.parse("2026-08-31T00:00:00Z");

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("dashboard.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        instance = UUID.randomUUID();
        instances = new InstanceRepository(jdbc);
        instances.save(instance, "orders", "http://127.0.0.1:8081", now);
        instances.markHealthy(instance, now, "ds-1");
        MetricSnapshotRepository snapshots = new MetricSnapshotRepository(jdbc);
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, "ds-1", now, 1_000, 600, 400, 10, 4, 4, 1));
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, "ds-1", now.plusSeconds(30), 500, 300, 200, 5, 2, 2, 1));
        totals = new DatasetTotalsRepository(jdbc);
        totals.save(new DatasetTotalsRepository.DatasetTotals("ds-1", now.plusSeconds(30), 15, 6, 9, 1_500, 600, 1, 900, "all-time"));
        totals.save(new DatasetTotalsRepository.DatasetTotals("ds-retired", now, 99, 9, 90, 9_900, 900, 9, 9_000, "all-time"));
        FailureEventRepository failures = new FailureEventRepository(jdbc);
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance, now.minusSeconds(7 * 24 * 3600), "poll", "boundary"));
        controller = new DashboardController(instances, snapshots, failures, totals);
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
    void overviewSumsTotalsOfRegisteredDatasets() {
        var overview = controller.overview();
        assertThat(overview.instanceCount()).isEqualTo(1);
        assertThat(overview.physicalUploads()).isEqualTo(9);
        assertThat(overview.trend()).extracting(DashboardView.TrendPoint::uploads).containsExactly(10L, 5L);
        assertThat(overview.uploads()).isEqualTo(15);
        assertThat(overview.logicalBytes()).isEqualTo(1_500);
        assertThat(overview.avoidedBytes()).isEqualTo(600);
        assertThat(overview.newUploads()).isEqualTo(9);
        assertThat(overview.contentCount()).isEqualTo(1);
        assertThat(overview.duplicateRate()).isEqualTo(6d / 15d);
        assertThat(overview.totalsScope()).isEqualTo("all-time");
    }

    @Test
    void totalsScopeIsMixedWhenSomeDatasetsCountSinceStart() {
        UUID older = UUID.randomUUID();
        instances.save(older, "older", "http://127.0.0.1:8082", now);
        instances.markHealthy(older, now, "instance:" + older);
        // An older instance reports no totalsScope; its counters reset on restart.
        totals.save(new DatasetTotalsRepository.DatasetTotals("instance:" + older, now, 3, 1, 2, 300, 100, 2, 200, null));

        assertThat(controller.overview().totalsScope()).isEqualTo("mixed");
        assertThat(controller.instanceStatus()).extracting(DashboardView.Instance::instanceName, DashboardView.Instance::totalsScope)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("orders", "all-time"),
                        org.assertj.core.groups.Tuple.tuple("older", "since-start"));
    }

    @Test
    void totalsScopeIsSinceStartWhenNoDatasetIsAllTime() {
        totals.save(new DatasetTotalsRepository.DatasetTotals("ds-1", now.plusSeconds(60), 15, 6, 9, 1_500, 600, 1, 900, "since-start"));

        assertThat(controller.overview().totalsScope()).isEqualTo("since-start");
    }

    @Test
    void totalsScopeIsAbsentWithoutDatasets(@TempDir java.nio.file.Path empty) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + empty.resolve("empty.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        var bare = new DashboardController(new InstanceRepository(jdbc), new MetricSnapshotRepository(jdbc),
                new FailureEventRepository(jdbc), new DatasetTotalsRepository(jdbc));

        assertThat(bare.overview().totalsScope()).isNull();
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
    void instanceStatusShowsItsDatasetTotals() {
        var view = controller.instanceStatus().getFirst();

        assertThat(view.datasetId()).isEqualTo("ds-1");
        assertThat(view.sharedDataset()).isFalse();
        assertThat(view.uploads()).isEqualTo(15);
        assertThat(view.newUploads()).isEqualTo(9);
        assertThat(view.duplicates()).isEqualTo(6);
        assertThat(view.contentCount()).isEqualTo(1);
        assertThat(view.physicalBytes()).isEqualTo(900);
        assertThat(view.avoidedBytes()).isEqualTo(600);
    }

    @Test
    void storageAvoidedLabelCarriesItsDefinition() throws Exception {
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/index.html"));
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/dashboard.js"));

        assertThat(html).contains("Storage avoided")
                .contains("title=\"Bytes that did not become an additional stored copy")
                .doesNotContainIgnoringCase("bytes avoided");
        assertThat(script).contains("sharedDataset").contains("shared dataset");
    }

    @Test
    void totalsScopeIsCaptioned() throws Exception {
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/index.html"));
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/dashboard.js"));

        assertThat(html).contains("id=\"totals-scope\"");
        assertThat(script).contains("totalsScope").contains("'all time'").contains("'since application start'")
                .contains("'some datasets since application start'");
    }

    @Test
    void staticDashboardIsServedFromResources() throws Exception {
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/static/index.html"))).isTrue();
    }
}
