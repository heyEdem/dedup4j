package com.edem.dedup4j.dashboard.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jDashboardPropertiesTest {
    @Test
    void hasSafeDefaults() {
        var properties = new Dedup4jDashboardProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getBasePath()).isEqualTo("/dedup4j/dashboard");
        assertThat(properties.getFailureLookback()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void normalizesBasePath() {
        var properties = new Dedup4jDashboardProperties();
        properties.setBasePath("dashboard/");
        assertThat(properties.getBasePath()).isEqualTo("/dashboard");
    }
}
