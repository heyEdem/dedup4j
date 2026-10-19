package com.edem.dedup4j.autoconfigure.persistence;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

final class Dedup4jSchemaValidator implements InitializingBean {

    static final String CHANGELOG = "classpath:db/dedup4j/db.changelog-master.yaml";

    static final String HISTORY_CHANGELOG = "classpath:db/dedup4j/history/db.changelog-history.yaml";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaInitialization mode;
    private final boolean historyEnabled;

    Dedup4jSchemaValidator(javax.sql.DataSource dataSource, Dedup4jProperties properties,
                           boolean historyActive) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.mode = properties.getPersistence().getInitializeSchema();
        this.historyEnabled = historyActive && properties.getHistory().isEnabled();
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        validateAssetContent();
        if (historyEnabled) {
            validateHistory();
        }
    }

    private void validateAssetContent() {
        try {
            jdbcTemplate.queryForList("select id, hash_algorithm, content_hash, size_bytes, object_key, "
                    + "storage_provider, bucket_or_container, content_type, original_extension, ref_count, "
                    + "created_at, updated_at, version "
                    + "from dedup4j_asset_content where 1 = 0");
        }
        catch (DataAccessException failure) {
            throw new IllegalStateException("dedup4j schema table 'dedup4j_asset_content' is missing "
                    + "for initialize-schema mode '" + mode.name().toLowerCase() + "'. Apply " + CHANGELOG
                    + " or set dedup4j.persistence.initialize-schema to an appropriate mode.", failure);
        }
    }

    private void validateHistory() {
        try {
            jdbcTemplate.queryForList("select id, dataset_id, created_at from dedup4j_history_dataset where 1 = 0");
            jdbcTemplate.queryForList("select sequence_no, dataset_id, instance_id, recorded_at, asset_content_id, "
                    + "hash_algorithm, content_hash, size_bytes, upload_delta, duplicate_delta, "
                    + "physical_write_delta, logical_bytes_delta, avoided_bytes_delta, total_uploads, "
                    + "total_duplicates, total_physical_writes, total_logical_bytes, total_avoided_bytes, "
                    + "compensates_sequence_no from dedup4j_upload_event where 1 = 0");
            jdbcTemplate.queryForList("select sequence_no, id, dataset_id, instance_id, recorded_at, operation, "
                    + "content_hash, object_key, message, compensates_failure_id "
                    + "from dedup4j_failure_event where 1 = 0");
        }
        catch (DataAccessException failure) {
            throw new IllegalStateException("dedup4j history tables are missing or incomplete "
                    + "for initialize-schema mode '" + mode.name().toLowerCase() + "'. Apply " + HISTORY_CHANGELOG
                    + ", or set dedup4j.history.enabled=false to run without persistent history.", failure);
        }
    }
}
