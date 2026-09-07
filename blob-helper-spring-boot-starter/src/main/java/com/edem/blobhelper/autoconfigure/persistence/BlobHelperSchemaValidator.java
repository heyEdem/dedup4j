package com.edem.blobhelper.autoconfigure.persistence;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

final class BlobHelperSchemaValidator implements InitializingBean {

    static final String CHANGELOG = "classpath:db/blob-helper/db.changelog-master.yaml";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaInitialization mode;

    BlobHelperSchemaValidator(javax.sql.DataSource dataSource, BlobHelperProperties properties) {
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
                    + "from blob_helper_asset_content where 1 = 0");
        }
        catch (DataAccessException failure) {
            throw new IllegalStateException("Blob Helper schema table 'blob_helper_asset_content' is missing "
                    + "for initialize-schema mode '" + mode.name().toLowerCase() + "'. Apply " + CHANGELOG
                    + " or set blob-helper.persistence.initialize-schema to an appropriate mode.", failure);
        }
    }
}
