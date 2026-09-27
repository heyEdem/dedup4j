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
    }
}
