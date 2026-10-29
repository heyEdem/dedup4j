package com.edem.dedup4j.reconcile;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

class ReferenceRepairSchemaH2Test extends AbstractReferenceRepairSchemaTest {

    @Override
    protected SingleConnectionDataSource newDataSource() {
        return new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", true);
    }
}
