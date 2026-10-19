package com.edem.dedup4j.dashboard.polling;

import com.edem.dedup4j.dashboard.api.DashboardController;
import com.edem.dedup4j.dashboard.persistence.DashboardDatabase;
import com.edem.dedup4j.dashboard.persistence.DatasetTotalsRepository;
import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetPollingTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Function<String, String> NO_FAILURES = query -> "[]";

    @TempDir Path tempDir;
    private HttpServer server;
    private JdbcTemplate jdbc;
    private InstanceRepository instances;
    private MetricSnapshotRepository snapshots;
    private FailureEventRepository failures;
    private DatasetTotalsRepository totals;
    private DashboardController dashboard;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        JdbcTemplate fresh = jdbc("dashboard.sqlite");
        new DashboardDatabase(fresh);
        wire(fresh);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void twoReplicasOneDatasetCountedOnce() {
        register("orders-a", metrics("ds-1", 10, 600, 6), NO_FAILURES);
        register("orders-b", metrics("ds-1", 10, 600, 6), NO_FAILURES);

        service(NOW).pollAll();

        var overview = dashboard.overview();
        assertThat(overview.uploads()).isEqualTo(10);
        assertThat(overview.duplicates()).isEqualTo(4);
        assertThat(overview.logicalBytes()).isEqualTo(1_000);
        assertThat(overview.avoidedBytes()).isEqualTo(400);
        assertThat(overview.contentCount()).isEqualTo(6);
        assertThat(overview.physicalBytes()).isEqualTo(600);
    }

    @Test
    void dashboardRestartRecordsBaseline() {
        String[] body = {metrics("ds-1", 8, 600, 6)};
        register("orders", ignored -> body[0], NO_FAILURES);
        service(NOW).pollAll();
        body[0] = metrics("ds-1", 10, 600, 6);

        service(NOW.plusSeconds(30)).pollAll();

        assertThat(snapshots.findAll()).hasSize(2).allSatisfy(snapshot -> {
            assertThat(snapshot.uploads()).isZero();
            assertThat(snapshot.logicalBytes()).isZero();
        });
        assertThat(dashboard.overview().uploads()).isEqualTo(10);
    }

    @Test
    void failuresIngestedOnceAcrossReplicasAndRestart() {
        String sameTwo = "[" + failure(1, NOW.minusSeconds(60)) + "," + failure(2, NOW.minusSeconds(30)) + "]";
        register("orders-a", metrics("ds-1", 10, 600, 6), query -> sameTwo);
        register("orders-b", metrics("ds-1", 10, 600, 6), query -> sameTwo);

        InstancePollingService polling = service(NOW);
        polling.pollAll();
        polling.pollAll();
        service(NOW.plusSeconds(60)).pollAll();

        assertThat(countByOrigin("application")).isEqualTo(2);
        assertThat(failures.cursor("ds-1")).isEqualTo(2);
    }

    @Test
    void olderInstanceWithoutDatasetIdIsOwnDataset() {
        List<String> queries = new CopyOnWriteArrayList<>();
        UUID legacyFailure = UUID.randomUUID();
        UUID older = register("older", oldMetrics(10, 4, 4, 1_000, 400, 600, 6), query -> {
            queries.add(query);
            return "[{\"id\":\"" + legacyFailure + "\",\"occurredAt\":\"" + NOW.minusSeconds(60)
                    + "\",\"operation\":\"store\",\"message\":\"boom\"}]";
        });
        UUID disabled = register("disabled", metrics(null, 3, 300, 3), NO_FAILURES);

        service(NOW).pollAll();
        service(NOW.plusSeconds(30)).pollAll();

        assertThat(instances.find(older).orElseThrow().status()).isEqualTo("HEALTHY");
        var olderTotals = totals.find("instance:" + older).orElseThrow();
        assertThat(olderTotals.uploads()).isEqualTo(10);
        assertThat(olderTotals.physicalWrites()).isEqualTo(6);
        assertThat(totals.find("instance:" + disabled)).isPresent();
        assertThat(countByOrigin("application")).isEqualTo(1);
        assertThat(queries).anyMatch(query -> query.contains("since="));
    }

    @Test
    void olderAndHistoryDisabledInstancesAreEachSummedOnceAndLabelledSinceStart() {
        register("orders-a", metrics("ds-1", 10, 600, 6), NO_FAILURES);
        register("orders-b", metrics("ds-1", 10, 600, 6), NO_FAILURES);
        UUID older = register("older", oldMetrics(7, 2, 2, 700, 200, 500, 5), NO_FAILURES);
        UUID disabled = register("disabled", metrics(null, "since-start", 3, 300, 3), NO_FAILURES);

        service(NOW).pollAll();
        service(NOW.plusSeconds(30)).pollAll();

        var overview = dashboard.overview();
        assertThat(overview.uploads()).isEqualTo(10 + 7 + 3);
        assertThat(overview.contentCount()).isEqualTo(6 + 5 + 3);
        assertThat(overview.physicalBytes()).isEqualTo(600 + 500 + 300);
        assertThat(overview.totalsScope()).isEqualTo("mixed");
        assertThat(dashboard.instanceStatus()).filteredOn(row -> row.instanceId().equals(older) || row.instanceId().equals(disabled))
                .hasSize(2).allSatisfy(row -> assertThat(row.totalsScope()).isEqualTo("since-start"));
    }

    @Test
    void upgradedInstanceCountsOnlyItsCurrentDataset() {
        String[] body = {oldMetrics(10, 4, 4, 1_000, 400, 600, 6)};
        register("orders", ignored -> body[0], NO_FAILURES);
        service(NOW).pollAll();
        body[0] = metrics("ds-1", 10, 600, 6);

        service(NOW.plusSeconds(30)).pollAll();

        assertThat(dashboard.overview().uploads()).isEqualTo(10);
        assertThat(dashboard.overview().contentCount()).isEqualTo(6);
    }

    @Test
    void pollFailuresKeepOriginPoll() {
        register("orders", metrics("ds-1", 10, 600, 6), paged(List.of(failure(1, NOW.minusSeconds(3_600)))));
        UUID unreachable = UUID.randomUUID();
        instances.save(unreachable, "gone", "http://127.0.0.1:1/gone/v1", NOW);

        service(NOW).pollAll();
        assertThat(countByOrigin("poll")).isEqualTo(1);
        assertThat(countByOrigin("application")).isEqualTo(1);

        Instant later = NOW.plus(Duration.ofDays(8));
        service(later).pollAll();

        assertThat(jdbc.queryForList("SELECT origin || '@' || occurred_at FROM failure_event", String.class))
                .containsExactly("poll@" + later);
    }

    @Test
    void expiredFailuresSkippedButCursorAdvances() {
        register("orders", metrics("ds-1", 10, 600, 6), paged(List.of(
                failure(1, NOW.minus(Duration.ofDays(8))), failure(2, NOW.minusSeconds(60)))));

        service(NOW).pollAll();

        assertThat(countByOrigin("application")).isEqualTo(1);
        assertThat(failures.cursor("ds-1")).isEqualTo(2);
    }

    @Test
    void backlogIngestedAcrossCyclesInOrder() {
        List<String> backlog = LongStream.rangeClosed(1, 1_500)
                .mapToObj(seq -> failure(seq, NOW.minusSeconds(3_600).plusMillis(seq))).toList();
        List<String> queries = new CopyOnWriteArrayList<>();
        Function<String, String> pages = paged(backlog);
        register("orders", metrics("ds-1", 10, 600, 6), query -> { queries.add(query); return pages.apply(query); });

        service(NOW).pollAll();
        assertThat(countByOrigin("application")).isEqualTo(1_000);
        assertThat(failures.cursor("ds-1")).isEqualTo(1_000);
        assertThat(queries).containsExactlyElementsOf(LongStream.range(0, 10)
                .mapToObj(page -> "afterSequence=" + page * 100 + "&limit=100").toList());

        service(NOW.plusSeconds(30)).pollAll();
        assertThat(countByOrigin("application")).isEqualTo(1_500);
        assertThat(failures.cursor("ds-1")).isEqualTo(1_500);
    }

    @Test
    void replicasWriteOneSnapshotPerDatasetPerCycle() {
        String[] body = {metrics("ds-1", 10, 600, 6)};
        register("orders-a", ignored -> body[0], NO_FAILURES);
        register("orders-b", ignored -> body[0], NO_FAILURES);

        InstancePollingService polling = service(NOW);
        polling.pollAll();
        body[0] = metrics("ds-1", 15, 600, 6);
        polling.pollAll();

        assertThat(snapshots.findAll()).extracting(MetricSnapshotRepository.Snapshot::uploads).containsExactly(0L, 5L);
        assertThat(instances.findAll()).allSatisfy(instance -> assertThat(instance.status()).isEqualTo("HEALTHY"));
    }

    @Test
    void malformedFailureIsSkippedAndCursorAdvances() {
        List<String> queries = new CopyOnWriteArrayList<>();
        Function<String, String> pages = paged(List.of(
                failure(1, NOW.minusSeconds(60)),
                "{\"sequenceNo\":2,\"id\":\"" + new UUID(0, 2) + "\",\"occurredAt\":\"yesterday\",\"operation\":\"store\",\"message\":\"bad time\"}",
                "{\"sequenceNo\":3,\"id\":\"not-a-uuid\",\"occurredAt\":\"" + NOW.minusSeconds(50) + "\",\"operation\":\"store\",\"message\":\"bad id\"}",
                "{\"sequenceNo\":4,\"id\":\"" + new UUID(0, 4) + "\",\"operation\":\"store\",\"message\":\"no time\"}",
                "{\"sequenceNo\":5,\"id\":\"" + new UUID(0, 5) + "\",\"occurredAt\":{\"at\":1},\"operation\":\"store\",\"message\":\"object time\"}",
                failure(6, NOW.minusSeconds(40))));
        register("orders", metrics("ds-1", 10, 600, 6), query -> { queries.add(query); return pages.apply(query); });

        service(NOW).pollAll();
        assertThat(countByOrigin("application")).isEqualTo(2);
        assertThat(failures.cursor("ds-1")).isEqualTo(6);

        service(NOW.plusSeconds(30)).pollAll();
        assertThat(queries).containsExactly("afterSequence=0&limit=100", "afterSequence=6&limit=100");
    }

    @Test
    void transientStoreErrorKeepsCursorAndRetriesPage() {
        failures = new FailureEventRepository(jdbc) {
            private boolean failed;
            @Override
            public boolean saveApplication(ApplicationFailure failure) {
                if (!failed) { failed = true; throw new CannotAcquireLockException("database is locked"); }
                return super.saveApplication(failure);
            }
        };
        register("orders", metrics("ds-1", 10, 600, 6), paged(List.of(
                failure(1, NOW.minusSeconds(60)), failure(2, NOW.minusSeconds(30)))));

        service(NOW).pollAll();
        assertThat(countByOrigin("application")).isZero();
        assertThat(failures.cursor("ds-1")).isZero();

        service(NOW.plusSeconds(30)).pollAll();
        assertThat(countByOrigin("application")).isEqualTo(2);
        assertThat(failures.cursor("ds-1")).isEqualTo(2);
    }

    @Test
    void applicationFailureIsAttributedToItsOriginInstance() {
        register("orders-a", metrics("ds-1", 10, 600, 6), query -> "[" + failure(1, NOW.minusSeconds(60)).replace("\"orders-a\"", "\"orders-b\"") + "]");
        UUID unreachable = UUID.randomUUID();
        instances.save(unreachable, "gone", "http://127.0.0.1:1/gone/v1", NOW);

        service(NOW).pollAll();

        assertThat(dashboard.failures(NOW.minusSeconds(3_600))).extracting("operation", "originInstanceId")
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("store", "orders-b"),
                        org.assertj.core.groups.Tuple.tuple("poll", unreachable.toString()));
    }

    @Test
    void legacyDatabaseUpgradeKeepsHistory() {
        JdbcTemplate legacy = jdbc("legacy.sqlite");
        createPreDatasetSchema(legacy);
        UUID instance = UUID.randomUUID();
        legacy.update("INSERT INTO dashboard_instance(instance_id,instance_name,advertised_url,registered_at,status) VALUES(?,?,?,?,?)",
                instance.toString(), "orders", url("orders"), NOW.toString(), "HEALTHY");
        for (int i = 0; i < 3; i++) {
            legacy.update("INSERT INTO metric_snapshot(instance_id,observed_at,logical_bytes,physical_bytes,avoided_bytes,uploads,duplicates,skipped_physical_writes,content_count) VALUES(?,?,?,?,?,?,?,?,?)",
                    instance.toString(), NOW.minusSeconds(300 - i * 30L).toString(), 100, 60, 40, 1, 0, 0, 1);
        }
        legacy.update("INSERT INTO failure_event(id,instance_id,occurred_at,operation,message) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), instance.toString(), NOW.minusSeconds(60).toString(), "poll", "refused");

        new DashboardDatabase(legacy);
        new DashboardDatabase(legacy);
        wire(legacy);

        assertThat(legacy.queryForList("SELECT dataset_id FROM metric_snapshot", String.class))
                .containsExactly("instance:" + instance, "instance:" + instance, "instance:" + instance);
        assertThat(legacy.queryForList("SELECT dataset_id || '/' || origin FROM failure_event", String.class))
                .containsExactly("instance:" + instance + "/poll");
        assertThat(dashboard.history(instance).points()).hasSize(3);

        server.createContext("/orders/v1/metrics", exchange -> respond(exchange, metrics("ds-1", 10, 600, 6)));
        server.createContext("/orders/v1/failures", exchange -> respond(exchange, "[]"));
        service(NOW).pollAll();

        assertThat(dashboard.history(instance).points()).hasSize(4);
    }

    @Test
    void sharedDatasetFlagged() {
        UUID first = register("orders-a", metrics("ds-1", 10, 600, 6), NO_FAILURES);
        UUID second = register("orders-b", metrics("ds-1", 10, 600, 6), NO_FAILURES);
        UUID solo = register("billing", metrics("ds-2", 3, 300, 3), NO_FAILURES);

        service(NOW).pollAll();

        var status = dashboard.instanceStatus();
        assertThat(status).filteredOn(row -> !row.instanceId().equals(solo)).hasSize(2).allSatisfy(row -> {
            assertThat(row.instanceId()).isIn(first, second);
            assertThat(row.datasetId()).isEqualTo("ds-1");
            assertThat(row.sharedDataset()).isTrue();
            assertThat(row.uploads()).isEqualTo(10);
            assertThat(row.contentCount()).isEqualTo(6);
        });
        assertThat(status).filteredOn(row -> row.instanceId().equals(solo)).singleElement()
                .satisfies(row -> assertThat(row.sharedDataset()).isFalse());
    }

    private void wire(JdbcTemplate template) {
        jdbc = template;
        instances = new InstanceRepository(template);
        snapshots = new MetricSnapshotRepository(template);
        failures = new FailureEventRepository(template);
        totals = new DatasetTotalsRepository(template);
        dashboard = new DashboardController(instances, snapshots, failures, totals);
    }

    private InstancePollingService service(Instant at) {
        return new InstancePollingService(instances, snapshots, failures, totals,
                new DashboardDatabaseProperties(null, Duration.ofSeconds(30), Duration.ofDays(7)),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private UUID register(String name, String metricsBody, Function<String, String> failureBody) {
        return register(name, ignored -> metricsBody, failureBody);
    }

    private UUID register(String name, Function<String, String> metricsBody, Function<String, String> failureBody) {
        server.createContext("/" + name + "/v1/metrics", exchange -> respond(exchange, metricsBody.apply(null)));
        server.createContext("/" + name + "/v1/failures", exchange -> respond(exchange, failureBody.apply(exchange.getRequestURI().getRawQuery())));
        UUID id = UUID.randomUUID();
        instances.save(id, name, url(name), NOW);
        return id;
    }

    private String url(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + "/v1";
    }

    private int countByOrigin(String origin) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM failure_event WHERE origin = ?", Integer.class, origin);
    }

    private JdbcTemplate jdbc(String file) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve(file), null, null));
    }

    /** Answers {@code afterSequence}/{@code limit} pages ascending, like the current management endpoint. */
    private static Function<String, String> paged(List<String> items) {
        return query -> {
            long after = 0;
            int limit = 100;
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=");
                if (parts[0].equals("afterSequence")) after = Long.parseLong(parts[1]);
                if (parts[0].equals("limit")) limit = Integer.parseInt(parts[1]);
            }
            List<String> page = new ArrayList<>();
            for (int i = (int) after; i < items.size() && page.size() < limit; i++) page.add(items.get(i));
            return "[" + String.join(",", page) + "]";
        };
    }

    /** The current management {@code /metrics} shape; a null dataset id is serialized as an explicit null. */
    private static String metrics(String datasetId, long uploads, long physicalBytes, long contentCount) {
        return metrics(datasetId, "all-time", uploads, physicalBytes, contentCount);
    }

    private static String metrics(String datasetId, String totalsScope, long uploads, long physicalBytes, long contentCount) {
        return "{\"datasetId\":" + (datasetId == null ? "null" : "\"" + datasetId + "\"")
                + ",\"totalsScope\":\"" + totalsScope + "\",\"uploads\":" + uploads + ",\"duplicates\":4,\"physicalWrites\":"
                + (uploads - 4) + ",\"skippedPhysicalWrites\":4,\"acceptedBytes\":1000,\"avoidedBytes\":400"
                + ",\"contentCount\":" + contentCount + ",\"physicalBytes\":" + physicalBytes + "}";
    }

    /** The {@code /metrics} shape of an instance that predates dataset ids. */
    private static String oldMetrics(long uploads, long duplicates, long skipped, long accepted,
                                     long avoided, long physical, long contentCount) {
        return "{\"uploads\":" + uploads + ",\"duplicates\":" + duplicates
                + ",\"skippedPhysicalWrites\":" + skipped + ",\"acceptedBytes\":" + accepted
                + ",\"avoidedBytes\":" + avoided + ",\"contentCount\":" + contentCount
                + ",\"physicalBytes\":" + physical + "}";
    }

    private static String failure(long sequenceNo, Instant occurredAt) {
        return "{\"sequenceNo\":" + sequenceNo + ",\"id\":\"" + new UUID(0, sequenceNo) + "\",\"occurredAt\":\""
                + occurredAt + "\",\"operation\":\"store\",\"message\":\"boom " + sequenceNo
                + "\",\"instanceId\":\"orders-a\",\"contentHash\":null,\"objectKey\":null}";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /** {@code DashboardDatabase} DDL as it was before dataset ids (copied from the previous release). */
    private static void createPreDatasetSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dashboard_instance (
                  instance_id TEXT PRIMARY KEY, instance_name TEXT NOT NULL, advertised_url TEXT NOT NULL,
                  registered_at TEXT NOT NULL, last_seen_at TEXT, status TEXT NOT NULL,
                  last_failure_at TEXT, last_failure_message TEXT)
                """);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS metric_snapshot (id INTEGER PRIMARY KEY AUTOINCREMENT, instance_id TEXT NOT NULL REFERENCES dashboard_instance(instance_id) ON DELETE CASCADE, observed_at TEXT NOT NULL, logical_bytes INTEGER NOT NULL, physical_bytes INTEGER NOT NULL, avoided_bytes INTEGER NOT NULL, uploads INTEGER NOT NULL, duplicates INTEGER NOT NULL, skipped_physical_writes INTEGER NOT NULL, content_count INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_metric_snapshot_instance_time ON metric_snapshot(instance_id, observed_at)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS failure_event (id TEXT PRIMARY KEY, instance_id TEXT NOT NULL REFERENCES dashboard_instance(instance_id) ON DELETE CASCADE, occurred_at TEXT NOT NULL, operation TEXT NOT NULL, message TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_failure_event_instance_time ON failure_event(instance_id, occurred_at)");
    }
}
