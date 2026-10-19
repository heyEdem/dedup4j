package com.edem.dedup4j.history;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/** Creates a fresh, empty database inside a shared PostgreSQL container. */
final class PostgresDatabases {

    private PostgresDatabases() {
    }

    /** Returns the JDBC URL of a newly created database. */
    static String create(PostgreSQLContainer container) {
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return container.getJdbcUrl().replaceFirst("/[^/?]*(\\?|$)", "/" + name + "$1");
    }
}
