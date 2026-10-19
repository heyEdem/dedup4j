package com.edem.dedup4j.history;

import java.util.UUID;

class UploadLedgerRepositoryH2Test extends AbstractUploadLedgerRepositoryTest {

    // LOCK_TIMEOUT (ms) must outlast the 500 ms hold in the concurrency tests
    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    @Override
    protected String jdbcUrl() {
        return url;
    }

    @Override
    protected String jdbcUser() {
        return "sa";
    }

    @Override
    protected String jdbcPassword() {
        return "";
    }
}
