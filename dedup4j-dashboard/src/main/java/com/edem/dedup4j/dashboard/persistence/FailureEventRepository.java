package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Failures shown by the dashboard. Origin {@code poll} rows are the dashboard's own reachability log; origin
 * {@code application} rows are display copies of the application's failure log, keyed by its failure id per dataset.
 * Retention removes both.
 */
@Repository
public class FailureEventRepository {
    public record Failure(UUID id, UUID instanceId, Instant occurredAt, String operation, String message) {}
    public record ApplicationFailure(UUID sourceFailureId, String datasetId, UUID instanceId, String originInstanceId,
                                     Instant occurredAt, String operation, String message) {}
    private final JdbcTemplate jdbc;
    public FailureEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Stores a poll failure under the instance's current dataset, or its pseudo-dataset when none is known yet. */
    public void save(Failure f) { jdbc.update("INSERT INTO failure_event(id,instance_id,occurred_at,operation,message,origin,dataset_id) VALUES(?,?,?,?,?,'poll',COALESCE((SELECT dataset_id FROM dashboard_instance WHERE instance_id=?), 'instance:' || ?))", f.id().toString(), f.instanceId().toString(), f.occurredAt().toString(), f.operation(), f.message(), f.instanceId().toString(), f.instanceId().toString()); }
    /** Returns false when the dataset already holds this failure (or, without a source id, never). */
    public boolean saveApplication(ApplicationFailure f) {
        return jdbc.update("INSERT OR IGNORE INTO failure_event(id,instance_id,occurred_at,operation,message,origin,dataset_id,source_failure_id,origin_instance_id) VALUES(?,?,?,?,?,'application',?,?,?)",
                UUID.randomUUID().toString(), f.instanceId().toString(), f.occurredAt().toString(), f.operation(), f.message(), f.datasetId(),
                f.sourceFailureId() == null ? null : f.sourceFailureId().toString(), f.originInstanceId()) == 1;
    }
    public List<Failure> findSince(Instant since) { return jdbc.query("SELECT * FROM failure_event WHERE julianday(occurred_at) >= julianday(?) ORDER BY occurred_at DESC", (r,n) -> new Failure(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("instance_id")), Instant.parse(r.getString("occurred_at")), r.getString("operation"), r.getString("message")), since.toString()); }
    public int deleteOlderThan(Instant cutoff) { return jdbc.update("DELETE FROM failure_event WHERE julianday(occurred_at) < julianday(?)", cutoff.toString()); }
    /** The highest application failure sequence number already ingested for the dataset; 0 when none. */
    public long cursor(String datasetId) { return jdbc.queryForList("SELECT last_sequence_no FROM failure_cursor WHERE dataset_id=?", Long.class, datasetId).stream().findFirst().orElse(0L); }
    public void saveCursor(String datasetId, long lastSequenceNo) { jdbc.update("INSERT INTO failure_cursor(dataset_id,last_sequence_no) VALUES(?,?) ON CONFLICT(dataset_id) DO UPDATE SET last_sequence_no=excluded.last_sequence_no", datasetId, lastSequenceNo); }
}
