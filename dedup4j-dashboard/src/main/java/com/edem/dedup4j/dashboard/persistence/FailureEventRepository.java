package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FailureEventRepository {
    public record Failure(UUID id, UUID instanceId, Instant occurredAt, String operation, String message) {}
    private final JdbcTemplate jdbc;
    public FailureEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(Failure f) { jdbc.update("INSERT INTO failure_event(id,instance_id,occurred_at,operation,message) VALUES(?,?,?,?,?)", f.id().toString(), f.instanceId().toString(), f.occurredAt().toString(), f.operation(), f.message()); }
    public List<Failure> findSince(Instant since) { return jdbc.query("SELECT * FROM failure_event WHERE julianday(occurred_at) >= julianday(?) ORDER BY occurred_at DESC", (r,n) -> new Failure(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("instance_id")), Instant.parse(r.getString("occurred_at")), r.getString("operation"), r.getString("message")), since.toString()); }
    public int deleteOlderThan(Instant cutoff) { return jdbc.update("DELETE FROM failure_event WHERE julianday(occurred_at) < julianday(?)", cutoff.toString()); }
}
