package com.edem.dedup4j.reconcile;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Append-only checks for the repair audit created by the main changelog. Subclasses supply a
 * DataSource over a fresh, empty database (H2 here, PostgreSQL in a Testcontainers subclass).
 */
abstract class AbstractReferenceRepairSchemaTest {

    private JdbcTemplate jdbc;
    private UUID contentId;
    private UUID repairId;

    /** A single-connection DataSource on an empty database that no other test uses. */
    protected abstract SingleConnectionDataSource newDataSource();

    @BeforeEach
    void migrate() throws Exception {
        SingleConnectionDataSource dataSource = newDataSource();
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection()));
        new Liquibase("db/dedup4j/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database).update("");
        // Liquibase leaves the shared connection in manual-commit mode; PostgreSQL would then abort the
        // whole transaction after the first rejected statement.
        dataSource.getConnection().setAutoCommit(true);
        jdbc = new JdbcTemplate(dataSource);
        contentId = UUID.randomUUID();
        jdbc.update("insert into dedup4j_asset_content (id, hash_algorithm, content_hash, size_bytes, object_key,"
                + " storage_provider, bucket_or_container, ref_count, created_at, updated_at, version)"
                + " values (?, 'SHA-256', 'h', 10, 'k', 'local', 'b', 1, current_timestamp, current_timestamp, 0)",
                contentId);
        repairId = insertRepair(null);
    }

    private UUID insertRepair(UUID compensates) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dedup4j_reference_repair values (?, ?, 2, 1, ?, 'i', current_timestamp, ?)",
                id, contentId, UUID.randomUUID(), compensates);
        return id;
    }

    @Test
    void updateRejected() {
        assertThatThrownBy(() -> jdbc.update("update dedup4j_reference_repair set repaired_count = 5"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deleteRejected() {
        assertThatThrownBy(() -> jdbc.update("delete from dedup4j_reference_repair"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void truncateRejected() {
        assertThatThrownBy(() -> jdbc.execute("truncate table dedup4j_reference_repair"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from dedup4j_reference_repair", Integer.class)).isEqualTo(1);
    }

    @Test
    void compensatingRowAppendsAndReferencesTheOriginal() {
        insertRepair(repairId);

        assertThat(jdbc.queryForObject("select count(*) from dedup4j_reference_repair where compensates_repair_id = ?",
                Integer.class, repairId)).isEqualTo(1);
    }

    @Test
    void repairOfUnknownContentRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into dedup4j_reference_repair values (?, ?, 2, 1, ?, 'i', current_timestamp, null)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }
}
