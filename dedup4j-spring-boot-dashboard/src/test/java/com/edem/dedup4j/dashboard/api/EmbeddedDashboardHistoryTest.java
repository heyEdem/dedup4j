package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.dashboard.autoconfigure.Dedup4jDashboardProperties;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.management.Dedup4jManagementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddedDashboardHistoryTest {
    @Test
    void overviewUsesHistoryTotals() throws Exception {
        UploadHistory history = new StubHistory(new HistoryTotals("all-time", "ds", 5, 2, 3, 500, 200));

        var overview = overview(history);

        assertThat(overview.path("uploads").asLong()).isEqualTo(5);
        assertThat(overview.path("newUploads").asLong()).isEqualTo(3);
        assertThat(overview.path("physicalWrites").asLong()).isEqualTo(3);
        assertThat(overview.path("logicalBytes").asLong()).isEqualTo(500);
        assertThat(overview.path("avoidedBytes").asLong()).isEqualTo(200);
        assertThat(overview.path("totalsScope").asString()).isEqualTo("all-time");
    }

    @Test
    void sinceStartScopeExposed() throws Exception {
        assertThat(overview(new DisabledUploadHistory()).path("totalsScope").asString()).isEqualTo("since-start");
    }

    private static JsonNode overview(UploadHistory history) throws Exception {
        var body = mvc(history).perform(get("/dedup4j/dashboard/api/v1/overview"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    private static MockMvc mvc(UploadHistory history) {
        var service = new EmbeddedDashboardSnapshotService(empty(MeterRegistry.class), empty(AssetContentRepository.class),
                single(UploadHistory.class, history), new Dedup4jProperties(), new Dedup4jManagementProperties());
        var controller = new EmbeddedDashboardController(service, new Dedup4jDashboardProperties(), since -> List.of());
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build())).build();
    }

    private static <T> ObjectProvider<T> single(Class<T> type, T value) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("value", value);
        return factory.getBeanProvider(type);
    }

    private static <T> ObjectProvider<T> empty(Class<T> type) {
        return new DefaultListableBeanFactory().getBeanProvider(type);
    }

    private record StubHistory(HistoryTotals totals) implements UploadHistory {
        @Override public void recordUpload(UploadContribution contribution) { }
        @Override public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) { }
        @Override public HistoryTotals totals() { return totals; }
        @Override public List<FailureRecord> recentFailures(Instant since, int limit) { return List.of(); }
        @Override public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) { return List.of(); }
        @Override public boolean persistent() { return true; }
    }
}
