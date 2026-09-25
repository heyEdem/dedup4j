package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricSnapshotRepository {
    public record Snapshot(UUID instanceId, Instant observedAt, long logicalBytes, long physicalBytes, long avoidedBytes, long uploads, long duplicates, long skippedPhysicalWrites, long contentCount) {}
    private final JdbcTemplate jdbc;
    public MetricSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(Snapshot s) { jdbc.update("INSERT INTO metric_snapshot(instance_id,observed_at,logical_bytes,physical_bytes,avoided_bytes,uploads,duplicates,skipped_physical_writes,content_count) VALUES(?,?,?,?,?,?,?,?,?)", s.instanceId().toString(), s.observedAt().toString(), s.logicalBytes(), s.physicalBytes(), s.avoidedBytes(), s.uploads(), s.duplicates(), s.skippedPhysicalWrites(), s.contentCount()); }
    public List<Snapshot> findByInstance(UUID id) { return jdbc.query("SELECT * FROM metric_snapshot WHERE instance_id=? ORDER BY observed_at", (r,n) -> new Snapshot(id, Instant.parse(r.getString("observed_at")), r.getLong("logical_bytes"), r.getLong("physical_bytes"), r.getLong("avoided_bytes"), r.getLong("uploads"), r.getLong("duplicates"), r.getLong("skipped_physical_writes"), r.getLong("content_count")), id.toString()); }
    public List<Snapshot> findAll() { return jdbc.query("SELECT * FROM metric_snapshot ORDER BY observed_at", (r,n) -> new Snapshot(UUID.fromString(r.getString("instance_id")), Instant.parse(r.getString("observed_at")), r.getLong("logical_bytes"), r.getLong("physical_bytes"), r.getLong("avoided_bytes"), r.getLong("uploads"), r.getLong("duplicates"), r.getLong("skipped_physical_writes"), r.getLong("content_count"))); }
}
