package com.edem.dedup4j.autoconfigure.history;

import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Fails startup on databases where the append-only history rule cannot be enforced. It runs
 * before the history Liquibase changelog.
 */
final class HistoryDatabaseGuard implements InitializingBean {

    private final DataSource dataSource;

    HistoryDatabaseGuard(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        }
        catch (SQLException failure) {
            throw new IllegalStateException("Unable to determine the database product for dedup4j history", failure);
        }
        if (!"PostgreSQL".equals(product) && !"H2".equals(product)) {
            throw new IllegalStateException("dedup4j history requires PostgreSQL or H2, but the database is '"
                    + product + "'. Set dedup4j.history.enabled=false to run without persistent history.");
        }
    }
}
