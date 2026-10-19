package com.edem.dedup4j.management;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.historystore.UploadContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class Dedup4jManagementHistoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/dedup4j/management/v1";

    private StubHistory history;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        history = new StubHistory();
        var management = new Dedup4jManagementProperties();
        management.setInstanceName("orders");
        var failureSource = new UploadHistoryFailureSource(history);
        var controller = new Dedup4jManagementController(management, new Dedup4jProperties(),
                provider(UploadHistory.class, history), provider(AssetContentRepository.class, null),
                provider(Dedup4jManagementSnapshot.FailureSource.class, failureSource));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON)).build();
    }

    @Test
    void metricsReadFromHistory() throws Exception {
        history.totals = new HistoryTotals("all-time", "ds-1", 5, 2, 3, 500, 200);

        JsonNode metrics = fetch("/metrics");

        assertThat(metrics.get("uploads").asLong()).isEqualTo(5);
        assertThat(metrics.get("duplicates").asLong()).isEqualTo(2);
        assertThat(metrics.get("physicalWrites").asLong()).isEqualTo(3);
        assertThat(metrics.get("skippedPhysicalWrites").asLong()).isEqualTo(2);
        assertThat(metrics.get("acceptedBytes").asLong()).isEqualTo(500);
        assertThat(metrics.get("avoidedBytes").asLong()).isEqualTo(200);
        assertThat(metrics.get("contentCount").asLong()).isZero();
        assertThat(metrics.get("physicalBytes").asLong()).isZero();
        assertThat(metrics.get("datasetId").asText()).isEqualTo("ds-1");
        assertThat(metrics.get("totalsScope").asText()).isEqualTo("all-time");
    }

    @Test
    void infoIncludesDatasetId() throws Exception {
        history.totals = new HistoryTotals("all-time", "ds-1", 0, 0, 0, 0, 0);

        assertThat(fetch("/info").get("datasetId").asText()).isEqualTo("ds-1");
    }

    @Test
    void disabledHistoryHasNullDatasetId() throws Exception {
        history.totals = new HistoryTotals("since-start", null, 1, 0, 1, 10, 0);

        assertThat(fetch("/info").get("datasetId").isNull()).isTrue();
        assertThat(fetch("/metrics").get("totalsScope").asText()).isEqualTo("since-start");
    }

    @Test
    void failuresPageWithCursorAndCappedLimit() throws Exception {
        fetch("/failures?afterSequence=7&limit=9999");

        assertThat(history.lastAfter).isEqualTo(7);
        assertThat(history.lastLimit).isEqualTo(500);
    }

    @Test
    void limitDefaultsToOneHundred() throws Exception {
        fetch("/failures?afterSequence=0");

        assertThat(history.lastLimit).isEqualTo(100);
    }

    @Test
    void initialBacklogLargerThanOnePage() throws Exception {
        history.failures = IntStream.rangeClosed(1, 250).mapToObj(StubHistory::failure).toList();

        assertThat(sequences("/failures?afterSequence=0&limit=100"))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 100).boxed().toList());
        assertThat(sequences("/failures?afterSequence=100&limit=100"))
                .containsExactlyElementsOf(IntStream.rangeClosed(101, 200).boxed().toList());
        assertThat(sequences("/failures?afterSequence=200&limit=100"))
                .containsExactlyElementsOf(IntStream.rangeClosed(201, 250).boxed().toList());
        assertThat(sequences("/failures?afterSequence=250&limit=100")).isEmpty();
    }

    @Test
    void failuresIncludeOrigin() throws Exception {
        history.failures = List.of(StubHistory.failure(1));

        JsonNode failure = fetch("/failures?afterSequence=0").get(0);

        assertThat(failure.get("sequenceNo").asLong()).isEqualTo(1);
        assertThat(failure.get("id").asText()).isNotBlank();
        assertThat(failure.get("occurredAt")).isNotNull();
        assertThat(failure.get("operation").asText()).isEqualTo("upload");
        assertThat(failure.get("message").asText()).isEqualTo("boom 1");
        assertThat(failure.get("instanceId").asText()).isEqualTo("inst-1");
        assertThat(failure.get("contentHash").asText()).isEqualTo("hash-1");
        assertThat(failure.get("objectKey").asText()).isEqualTo("key-1");
    }

    @Test
    void sinceModeStaysDescendingDisplay() throws Exception {
        history.recent = List.of(StubHistory.failure(9), StubHistory.failure(8));

        assertThat(sequences("/failures?since=2026-01-01T00:00:00Z")).containsExactly(9, 8);
        assertThat(history.lastSince).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(sequences("/failures?since=2026-01-01T00:00:00Z&limit=1")).containsExactly(9);
    }

    private JsonNode fetch(String path) throws Exception {
        String body = mvc.perform(get(BASE + path)).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private List<Integer> sequences(String path) throws Exception {
        List<Integer> result = new ArrayList<>();
        fetch(path).forEach(node -> result.add(node.get("sequenceNo").asInt()));
        return result;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton("bean", value);
        }
        return factory.getBeanProvider(type);
    }

    static final class StubHistory implements UploadHistory {
        HistoryTotals totals = new HistoryTotals("all-time", "ds", 0, 0, 0, 0, 0);
        List<FailureRecord> failures = List.of();
        List<FailureRecord> recent = List.of();
        long lastAfter = -1;
        int lastLimit = -1;
        Instant lastSince;

        static FailureRecord failure(int seq) {
            return new FailureRecord(seq, UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"),
                    "upload", "boom " + seq, "inst-" + seq, "hash-" + seq, "key-" + seq);
        }

        @Override public void recordUpload(UploadContribution contribution) { }
        @Override public void recordFailure(String operation, String contentHash, String objectKey,
                                            Throwable failure) { }
        @Override public HistoryTotals totals() { return totals; }

        @Override
        public List<FailureRecord> recentFailures(Instant since, int limit) {
            lastSince = since;
            lastLimit = limit;
            return recent;
        }

        @Override
        public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) {
            lastAfter = afterSequenceNo;
            lastLimit = limit;
            return failures.stream().filter(f -> f.sequenceNo() > afterSequenceNo).limit(limit).toList();
        }

        @Override public boolean persistent() { return true; }
    }
}
