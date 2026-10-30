package com.edem.dedup4j.service;

import java.util.Map;
import java.util.UUID;

class TransactionalBlobReferencesH2Test extends AbstractTransactionalBlobReferencesTest {

    @Override
    protected Map<String, Object> connectionProperties() {
        return Map.of("jakarta.persistence.jdbc.url",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }

    @Override
    protected String lockWaitersQuery() {
        // H2 reports no blocker for an insert waiting on an uncommitted duplicate key, only the running statement.
        return "select count(*) from information_schema.sessions where blocker_id is not null "
                + "or lower(executing_statement) like 'insert into dedup4j_asset_content%'";
    }
}
