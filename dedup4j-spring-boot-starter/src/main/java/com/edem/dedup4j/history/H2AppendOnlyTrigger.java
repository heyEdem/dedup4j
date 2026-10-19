package com.edem.dedup4j.history;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Rejects {@code UPDATE} and {@code DELETE} on the H2 history tables.
 *
 * <p>Existing H2 databases store this fully-qualified class name in their trigger definitions.
 * Never rename or move this class: those databases would fail to load the trigger.</p>
 */
public class H2AppendOnlyTrigger implements Trigger {

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("dedup4j history tables are append-only");
    }
}
