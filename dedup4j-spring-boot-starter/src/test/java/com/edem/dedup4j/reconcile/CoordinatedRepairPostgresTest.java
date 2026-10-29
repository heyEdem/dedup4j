package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.history.PostgresDatabases;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

@Tag("postgres")
@Testcontainers
class CoordinatedRepairPostgresTest extends AbstractCoordinatedRepairTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Override
    protected Map<String, Object> connectionProperties() {
        return Map.of(
                "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
                "jakarta.persistence.jdbc.url", PostgresDatabases.create(POSTGRES),
                "jakarta.persistence.jdbc.user", POSTGRES.getUsername(),
                "jakarta.persistence.jdbc.password", POSTGRES.getPassword());
    }

    @Override
    protected String databaseProduct() {
        return "PostgreSQL";
    }

    @Override
    protected String lockWaitersQuery() {
        return "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()";
    }
}
