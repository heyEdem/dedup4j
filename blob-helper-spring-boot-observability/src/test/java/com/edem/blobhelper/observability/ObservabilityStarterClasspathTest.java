package com.edem.blobhelper.observability;

import com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardAutoConfiguration;
import com.edem.blobhelper.management.BlobHelperManagementAutoConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservabilityStarterClasspathTest {

    @Test
    void includesManagementAndEmbeddedDashboard() {
        assertNotNull(BlobHelperManagementAutoConfiguration.class);
        assertNotNull(BlobHelperDashboardAutoConfiguration.class);
        assertNotNull(getClass().getResource("/static/blob-helper/dashboard/index.html"));
    }

    @Test
    void excludesStandaloneDashboard() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.dashboard.BlobHelperDashboardApplication"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.dashboard.persistence.MetricSnapshotRepository"));
    }
}
