package com.edem.dedup4j.dashboard.persistence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.edem.dedup4j.dashboard.polling.DashboardDatabaseProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DashboardDatabaseProperties.class)
public class DashboardDatabase {
    public DashboardDatabase(JdbcTemplate jdbcTemplate) {
        initialize(jdbcTemplate);
    }

    private void initialize(JdbcTemplate jdbcTemplate) {
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
        migrateToDatasets(jdbcTemplate);
    }

    /** Adds dataset columns to databases created before dataset ids; idempotent, so it runs on every start. */
    private void migrateToDatasets(JdbcTemplate jdbcTemplate) {
        addColumnIfMissing(jdbcTemplate, "dashboard_instance", "dataset_id", "TEXT");
        addColumnIfMissing(jdbcTemplate, "metric_snapshot", "dataset_id", "TEXT");
        addColumnIfMissing(jdbcTemplate, "failure_event", "dataset_id", "TEXT");
        // poll: the dashboard's own reachability log; application: a display copy of the application's failure log
        addColumnIfMissing(jdbcTemplate, "failure_event", "origin", "TEXT NOT NULL DEFAULT 'poll'");
        addColumnIfMissing(jdbcTemplate, "failure_event", "source_failure_id", "TEXT");
        addColumnIfMissing(jdbcTemplate, "failure_event", "origin_instance_id", "TEXT");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_failure_event_source ON failure_event(dataset_id, source_failure_id) WHERE source_failure_id IS NOT NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_metric_snapshot_dataset_time ON metric_snapshot(dataset_id, observed_at)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS failure_cursor (dataset_id TEXT PRIMARY KEY, last_sequence_no INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dataset_totals (dataset_id TEXT PRIMARY KEY, observed_at TEXT NOT NULL, uploads INTEGER NOT NULL, duplicates INTEGER NOT NULL, physical_writes INTEGER NOT NULL, logical_bytes INTEGER NOT NULL, avoided_bytes INTEGER NOT NULL, content_count INTEGER NOT NULL, physical_bytes INTEGER NOT NULL, totals_scope TEXT)");
        // Rows written before dataset ids were per-process deltas, so each belongs to its instance's pseudo-dataset.
        // These are dashboard display tables, not audit tables.
        jdbcTemplate.update("UPDATE metric_snapshot SET dataset_id = 'instance:' || instance_id WHERE dataset_id IS NULL");
        jdbcTemplate.update("UPDATE failure_event SET dataset_id = 'instance:' || instance_id WHERE dataset_id IS NULL");
    }

    private void addColumnIfMissing(JdbcTemplate jdbcTemplate, String table, String column, String definition) {
        boolean present = jdbcTemplate.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class).contains(column);
        if (!present) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
