package com.edem.dedup4j.autoconfigure.persistence;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

final class Dedup4jSchemaValidator implements InitializingBean {

    static final String CHANGELOG = "classpath:db/dedup4j/db.changelog-master.yaml";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaInitialization mode;

    Dedup4jSchemaValidator(javax.sql.DataSource dataSource, Dedup4jProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.mode = properties.getPersistence().getInitializeSchema();
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
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
}
