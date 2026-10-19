package com.edem.dedup4j.history;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

class HistorySchemaH2Test extends AbstractHistorySchemaTest {

    @Override
    protected SingleConnectionDataSource newDataSource() {
        return new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", true);
    }
}
