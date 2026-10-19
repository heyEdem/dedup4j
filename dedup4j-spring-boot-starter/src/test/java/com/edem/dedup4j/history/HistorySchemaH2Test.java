package com.edem.dedup4j.history;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistorySchemaH2Test {

    private JdbcTemplate jdbc;
    private UUID datasetId;

    @BeforeEach
    void migrate() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", true);
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection()));
        for (String changelog : new String[] {
                "db/dedup4j/changes/001-create-asset-content.yaml",
                "db/dedup4j/history/db.changelog-history.yaml"}) {
            new Liquibase(changelog, new ClassLoaderResourceAccessor(), database).update("");
        }
        jdbc = new JdbcTemplate(dataSource);
        datasetId = jdbc.queryForObject("select dataset_id from dedup4j_history_dataset", UUID.class);
        seed();
    }

    private void seed() {
        UUID assetId = UUID.randomUUID();
        jdbc.update("insert into dedup4j_upload_event values (1, ?, 'i', current_timestamp, ?, 'SHA-256', 'h', 10,"
                + " 1, 0, 1, 10, 0, 1, 0, 1, 10, 0, null)", datasetId, assetId);
        jdbc.update("insert into dedup4j_failure_event values (1, ?, ?, 'i', current_timestamp, 'upload', null,"
                + " null, 'boom', null)", UUID.randomUUID(), datasetId);
    }

    @Test
    void datasetRowExistsExactlyOnce() {
        assertThat(jdbc.queryForObject("select count(*) from dedup4j_history_dataset", Integer.class)).isEqualTo(1);
        assertThat(datasetId).isNotNull();
    }

    @Test
    void secondDatasetRowRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into dedup4j_history_dataset values (2, ?, current_timestamp)", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dedup4j_history_dataset", "dedup4j_upload_event", "dedup4j_failure_event"})
    void updateRejected(String table) {
        String column = table.equals("dedup4j_history_dataset") ? "created_at" : "instance_id";
        String value = table.equals("dedup4j_history_dataset") ? "current_timestamp" : "'x'";
        assertThatThrownBy(() -> jdbc.update("update " + table + " set " + column + " = " + value))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dedup4j_history_dataset", "dedup4j_upload_event", "dedup4j_failure_event"})
    void deleteRejected(String table) {
        assertThatThrownBy(() -> jdbc.update("delete from " + table))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dedup4j_history_dataset", "dedup4j_upload_event", "dedup4j_failure_event"})
    void truncateRejected(String table) {
        assertThatThrownBy(() -> jdbc.execute("truncate table " + table))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(1);
    }

    @Test
    void insertAllowed() {
        jdbc.update("insert into dedup4j_upload_event values (2, ?, 'i', current_timestamp, ?, 'SHA-256', 'h', 10,"
                + " 1, 0, 1, 10, 0, 2, 0, 2, 20, 0, null)", datasetId, UUID.randomUUID());
        jdbc.update("insert into dedup4j_failure_event values (2, ?, ?, 'i', current_timestamp, 'delete', 'h',"
                + " 'k', 'boom', null)", UUID.randomUUID(), datasetId);
        assertThat(jdbc.queryForObject("select count(*) from dedup4j_upload_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from dedup4j_failure_event", Integer.class)).isEqualTo(2);
    }
}
