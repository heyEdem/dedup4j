package com.edem.dedup4j.reconcile;

import java.util.Map;
import java.util.UUID;

class CoordinatedRepairH2Test extends AbstractCoordinatedRepairTest {

    @Override
    protected Map<String, Object> connectionProperties() {
        return Map.of("jakarta.persistence.jdbc.url",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }

    @Override
    protected String databaseProduct() {
        return "H2";
    }

    @Override
    protected String lockWaitersQuery() {
        return "select count(*) from information_schema.sessions where blocker_id is not null";
    }
}
