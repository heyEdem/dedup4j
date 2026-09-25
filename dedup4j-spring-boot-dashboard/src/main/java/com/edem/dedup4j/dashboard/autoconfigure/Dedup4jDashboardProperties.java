package com.edem.dedup4j.dashboard.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "dedup4j.dashboard")
public class Dedup4jDashboardProperties {
    private boolean enabled = true;
    private String basePath = "/dedup4j/dashboard";
    private Duration failureLookback = Duration.ofDays(7);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) throw new IllegalArgumentException("basePath must not be blank");
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        basePath = normalized.replaceAll("/+$", "");
        this.basePath = basePath.isEmpty() ? "/" : basePath;
    }
    public Duration getFailureLookback() { return failureLookback; }
    public void setFailureLookback(Duration failureLookback) {
        if (failureLookback == null || failureLookback.isNegative()) throw new IllegalArgumentException("failureLookback must not be negative");
        this.failureLookback = failureLookback;
    }
}
