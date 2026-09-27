package com.edem.dedup4j.observability;

import com.edem.dedup4j.dashboard.autoconfigure.Dedup4jDashboardAutoConfiguration;
import com.edem.dedup4j.management.Dedup4jManagementAutoConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservabilityStarterClasspathTest {

    @Test
    void includesManagementAndEmbeddedDashboard() {
        assertNotNull(Dedup4jManagementAutoConfiguration.class);
        assertNotNull(Dedup4jDashboardAutoConfiguration.class);
        assertNotNull(getClass().getResource("/static/dedup4j/dashboard/index.html"));
    }

    @Test
    void excludesStandaloneDashboard() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.dedup4j.dashboard.Dedup4jDashboardApplication"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository"));
    }
}
