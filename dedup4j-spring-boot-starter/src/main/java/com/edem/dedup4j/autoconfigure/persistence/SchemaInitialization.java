package com.edem.dedup4j.autoconfigure.persistence;

import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;

import javax.sql.DataSource;

public enum SchemaInitialization {
    EMBEDDED,
    ALWAYS,
    NEVER;

    public boolean shouldInitialize(DataSource dataSource) {
        return this == ALWAYS || (this == EMBEDDED && EmbeddedDatabaseConnection.isEmbedded(dataSource));
    }
}
