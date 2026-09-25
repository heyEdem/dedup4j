package com.edem.dedup4j.dashboard;

import com.edem.dedup4j.dashboard.api.EmbeddedDashboardController;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDashboardIntegrationTest {
    @Test
    void redirectsTheNoSlashRootSoRelativeAssetsResolveUnderTheDashboardPath() {
        assertThat(EmbeddedDashboardController.rootViewName("/dedup4j/dashboard", false))
                .isEqualTo("redirect:/dedup4j/dashboard/");
        assertThat(EmbeddedDashboardController.rootViewName("/dedup4j/dashboard", true))
                .isEqualTo("forward:/dedup4j/dashboard/index.html");
    }

    @Test
    void packagesTheUiWithRelativeApiRequestsAndNoSqlitePersistence() throws Exception {
        var index = getClass().getResourceAsStream("/static/dedup4j/dashboard/index.html");
        var script = getClass().getResourceAsStream("/static/dedup4j/dashboard/js/dashboard.js");
        assertThat(index).isNotNull();
        assertThat(script).isNotNull();
        assertThat(new String(index.readAllBytes(), StandardCharsets.UTF_8)).contains("Make every byte count.");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8)).contains("/api/v1/overview");
        assertThat(getClass().getResource("/dedup4j-dashboard.sqlite")).isNull();
    }
}
