package com.edem.dedup4j.dashboard.autoconfigure;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.dashboard.api.EmbeddedDashboardController;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.management.Dedup4jManagementAutoConfiguration;
import com.edem.dedup4j.management.Dedup4jManagementSnapshot;
import com.edem.dedup4j.management.UploadHistoryFailureSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jDashboardAutoConfigurationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Dedup4jDashboardAutoConfiguration.class))
            .withUserConfiguration(RequiredProperties.class);

    @Test
    void isEnabledByDefaultInServletWebApplications() {
        context.run(c -> assertThat(c).hasSingleBean(EmbeddedDashboardController.class));
    }

    @Test
    void canBeDisabled() {
        context.withPropertyValues("dedup4j.dashboard.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(EmbeddedDashboardController.class));
    }

    @Test
    void failuresComeFromHistoryWhenManagementIsDisabled() {
        context.withUserConfiguration(HistoryConfiguration.class)
                .run(c -> assertThat(c.getBean(EmbeddedDashboardController.class).failures(Instant.EPOCH))
                        .extracting(Dedup4jManagementSnapshot.Failure::message).containsExactly("history failure"));
    }

    @Test
    void failuresComeFromHistoryWhenManagementIsEnabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        Dedup4jManagementAutoConfiguration.class, Dedup4jDashboardAutoConfiguration.class))
                .withUserConfiguration(HistoryConfiguration.class)
                .withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local")
                .run(c -> {
                    assertThat(c).hasSingleBean(UploadHistoryFailureSource.class);
                    assertThat(c.getBean(EmbeddedDashboardController.class).failures(Instant.EPOCH))
                            .extracting(Dedup4jManagementSnapshot.Failure::message).containsExactly("history failure");
                });
    }

    @Test
    void userFailureSourceWinsOverHistory() {
        context.withUserConfiguration(HistoryConfiguration.class, UserFailureSourceConfiguration.class)
                .run(c -> assertThat(c.getBean(EmbeddedDashboardController.class).failures(Instant.EPOCH))
                        .extracting(Dedup4jManagementSnapshot.Failure::message).containsExactly("user failure"));
    }

    @Configuration(proxyBeanMethods = false)
    static class HistoryConfiguration {
        @Bean UploadHistory uploadHistory() { return new OneFailureHistory(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserFailureSourceConfiguration {
        @Bean Dedup4jManagementSnapshot.FailureSource userFailureSource() {
            return since -> List.of(new Dedup4jManagementSnapshot.Failure(
                    UUID.randomUUID(), Instant.EPOCH, "upload", "user failure", "i", null, null, 0));
        }
    }

    /** A persistent history holding exactly one failure. */
    static final class OneFailureHistory implements UploadHistory {
        private final FailureRecord failure = new FailureRecord(1, UUID.randomUUID(),
                Instant.parse("2026-10-19T00:00:00Z"), "upload", "history failure", "i", null, null);
        @Override public void recordUpload(UploadContribution contribution) { }
        @Override public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) { }
        @Override public HistoryTotals totals() { return new HistoryTotals("all-time", null, 0, 0, 0, 0, 0); }
        @Override public List<FailureRecord> recentFailures(Instant since, int limit) { return List.of(failure); }
        @Override public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) { return List.of(failure); }
        @Override public boolean persistent() { return true; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredProperties {
        @Bean Dedup4jProperties dedup4jProperties() { return new Dedup4jProperties(); }
    }
}
