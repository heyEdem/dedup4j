package com.edem.dedup4j.dashboard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The latest totals each dataset reported: a display cache, upserted on every successful poll. */
@Repository
public class DatasetTotalsRepository {
    public record DatasetTotals(String datasetId, Instant observedAt, long uploads, long duplicates, long physicalWrites,
                                long logicalBytes, long avoidedBytes, long contentCount, long physicalBytes, String totalsScope) {}
    private final JdbcTemplate jdbc;
    public DatasetTotalsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(DatasetTotals t) {
        jdbc.update("INSERT INTO dataset_totals(dataset_id,observed_at,uploads,duplicates,physical_writes,logical_bytes,avoided_bytes,content_count,physical_bytes,totals_scope) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(dataset_id) DO UPDATE SET observed_at=excluded.observed_at, uploads=excluded.uploads, duplicates=excluded.duplicates, physical_writes=excluded.physical_writes, logical_bytes=excluded.logical_bytes, avoided_bytes=excluded.avoided_bytes, content_count=excluded.content_count, physical_bytes=excluded.physical_bytes, totals_scope=excluded.totals_scope",
                t.datasetId(), t.observedAt().toString(), t.uploads(), t.duplicates(), t.physicalWrites(), t.logicalBytes(), t.avoidedBytes(), t.contentCount(), t.physicalBytes(), t.totalsScope());
    }
    public Optional<DatasetTotals> find(String datasetId) { return jdbc.query("SELECT * FROM dataset_totals WHERE dataset_id=?", mapper(), datasetId).stream().findFirst(); }
    /** Datasets some registered instance currently reports, so a dataset an instance has moved away from is not counted again. */
    public List<DatasetTotals> findCurrent() { return jdbc.query("SELECT * FROM dataset_totals WHERE dataset_id IN (SELECT dataset_id FROM dashboard_instance) ORDER BY dataset_id", mapper()); }
    private RowMapper<DatasetTotals> mapper() { return (r, n) -> new DatasetTotals(r.getString("dataset_id"), Instant.parse(r.getString("observed_at")), r.getLong("uploads"), r.getLong("duplicates"), r.getLong("physical_writes"), r.getLong("logical_bytes"), r.getLong("avoided_bytes"), r.getLong("content_count"), r.getLong("physical_bytes"), r.getString("totals_scope")); }
}
