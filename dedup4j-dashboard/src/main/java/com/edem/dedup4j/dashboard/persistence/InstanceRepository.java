package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InstanceRepository {
    public record Instance(UUID id, String name, String url, Instant registeredAt, Instant lastSeenAt,
                            String status, Instant lastFailureAt, String lastFailureMessage) {}
    private final JdbcTemplate jdbc;
    public InstanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Instance save(UUID id, String name, String url, Instant registeredAt) {
        jdbc.update("INSERT INTO dashboard_instance(instance_id,instance_name,advertised_url,registered_at,status) VALUES(?,?,?,?,?) ON CONFLICT(instance_id) DO UPDATE SET instance_name=excluded.instance_name, advertised_url=excluded.advertised_url", id.toString(), name, url, registeredAt.toString(), "REGISTERED");
        return find(id).orElseThrow();
    }
    public java.util.Optional<Instance> find(UUID id) { return jdbc.query("SELECT * FROM dashboard_instance WHERE instance_id=?", mapper(), id.toString()).stream().findFirst(); }
    public List<Instance> findAll() { return jdbc.query("SELECT * FROM dashboard_instance ORDER BY instance_name", mapper()); }
    public void markHealthy(UUID id, Instant seen) { jdbc.update("UPDATE dashboard_instance SET status='HEALTHY', last_seen_at=?, last_failure_at=NULL, last_failure_message=NULL WHERE instance_id=?", seen.toString(), id.toString()); }
    public void markFailure(UUID id, Instant at, String message) { jdbc.update("UPDATE dashboard_instance SET status='DISCONNECTED', last_failure_at=?, last_failure_message=? WHERE instance_id=?", at.toString(), message, id.toString()); }
    private org.springframework.jdbc.core.RowMapper<Instance> mapper() { return (r, n) -> new Instance(UUID.fromString(r.getString("instance_id")), r.getString("instance_name"), r.getString("advertised_url"), Instant.parse(r.getString("registered_at")), parse(r.getString("last_seen_at")), r.getString("status"), parse(r.getString("last_failure_at")), r.getString("last_failure_message")); }
    private static Instant parse(String value) { return value == null ? null : Instant.parse(value); }
}
