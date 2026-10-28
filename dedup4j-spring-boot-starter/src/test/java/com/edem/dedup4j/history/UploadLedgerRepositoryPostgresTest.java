package com.edem.dedup4j.history;

import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
class UploadLedgerRepositoryPostgresTest extends AbstractUploadLedgerRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private String url;

    @Override
    protected String jdbcUrl() {
        if (url == null) {
            url = PostgresDatabases.create(POSTGRES);
        }
        return url;
    }

    @Override
    protected String jdbcUser() {
        return POSTGRES.getUsername();
    }

    @Override
    protected String jdbcPassword() {
        return POSTGRES.getPassword();
    }
}
