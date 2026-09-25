package com.edem.dedup4j.dashboard.polling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dedup4j.dashboard")
public record DashboardDatabaseProperties(String databasePath, Duration pollingInterval, Duration failureRetention) {
    public DashboardDatabaseProperties {
        if (databasePath == null) databasePath = "./blob-helper-dashboard.sqlite";
        if (pollingInterval == null) pollingInterval = Duration.ofSeconds(30);
        if (failureRetention == null) failureRetention = Duration.ofDays(7);
    }
}
