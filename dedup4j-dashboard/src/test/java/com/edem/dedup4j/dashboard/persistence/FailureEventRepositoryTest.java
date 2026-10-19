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
        snapshots.save(new MetricSnapshotRepository.Snapshot(instance, "instance:" + instance, now.minusSeconds(3600), 10, 5, 5, 1, 1, 1, 1));

        assertThat(failures.deleteOlderThan(now.minusSeconds(7 * 24 * 3600))).isEqualTo(1);
        assertThat(failures.findSince(now.minusSeconds(7 * 24 * 3600))).hasSize(1);
        assertThat(snapshots.findHistory(instance, null)).hasSize(1);
    }

    @Test
    void applicationFailureIsStoredOncePerDataset() {
        UUID source = UUID.randomUUID();
        var failure = new FailureEventRepository.ApplicationFailure(source, "ds-1", instance, "orders-a", now, "store", "boom");

        assertThat(failures.saveApplication(failure)).isTrue();
        assertThat(failures.saveApplication(failure)).isFalse();
        assertThat(failures.saveApplication(new FailureEventRepository.ApplicationFailure(source, "ds-2", instance, "orders-a", now, "store", "boom"))).isTrue();
        assertThat(failures.findSince(now.minusSeconds(1))).hasSize(2);
    }

    @Test
    void failureCursorStartsAtZeroAndKeepsLatestSequence() {
        assertThat(failures.cursor("ds-1")).isZero();
        failures.saveCursor("ds-1", 5);
        failures.saveCursor("ds-1", 7);

        assertThat(failures.cursor("ds-1")).isEqualTo(7);
        assertThat(failures.cursor("ds-2")).isZero();
    }
}
