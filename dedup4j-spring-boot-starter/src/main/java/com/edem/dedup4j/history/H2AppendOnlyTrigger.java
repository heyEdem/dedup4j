package com.edem.dedup4j.history;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

public class H2AppendOnlyTrigger implements Trigger {

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("dedup4j history tables are append-only");
    }
}
