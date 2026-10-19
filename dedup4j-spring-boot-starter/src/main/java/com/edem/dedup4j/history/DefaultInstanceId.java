package com.edem.dedup4j.history;

import java.util.UUID;

/**
 * The instance id used when neither {@code dedup4j.history.instance-id} nor
 * {@code dedup4j.management.instance-id} is set: one random UUID per JVM, shared by history rows
 * and the management API so failures are attributed to the id the management API reports.
 */
public final class DefaultInstanceId {

    private static final String VALUE = UUID.randomUUID().toString();

    private DefaultInstanceId() {
    }

    public static String value() {
        return VALUE;
    }
}
