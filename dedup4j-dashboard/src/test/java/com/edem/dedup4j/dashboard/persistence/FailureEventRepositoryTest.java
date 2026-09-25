package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class FailureEventRepositoryTest {
    @TempDir Path tempDir;
    private FailureEventRepository failures;
    private MetricSnapshotRepository snapshots;
    private final UUID instance = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-31T00:00:00Z");

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("dashboard.sqlite"), null, null));
        new DashboardDatabase(jdbc);
        new InstanceRepository(jdbc).save(instance, "orders", "http://127.0.0.1:8081", now);
        failures = new FailureEventRepository(jdbc);
        snapshots = new MetricSnapshotRepository(jdbc);
    }

    @Test
    void retainsFailuresForSevenDaysWithoutDeletingSnapshots() {
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance, now.minusSeconds(7 * 24 * 3600 + 1), "poll", "old"));
        failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance, now.minusSeconds(3600), "poll", "recent"));
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, now.minusSeconds(3600), 10, 5, 5, 1, 1, 1, 1));

        assertThat(failures.deleteOlderThan(now.minusSeconds(7 * 24 * 3600))).isEqualTo(1);
        assertThat(failures.findSince(now.minusSeconds(7 * 24 * 3600))).hasSize(1);
        assertThat(snapshots.findByInstance(instance)).hasSize(1);
    }
}
