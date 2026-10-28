package com.edem.dedup4j.history;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
class HistorySchemaPostgresTest extends AbstractHistorySchemaTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Override
    protected SingleConnectionDataSource newDataSource() {
        return new SingleConnectionDataSource(PostgresDatabases.create(POSTGRES),
                POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    }
}
