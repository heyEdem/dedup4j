package com.edem.dedup4j.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jMetricsTest {

    @Test
    void recordsUploadOutcomesAndByteSavings() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Dedup4jMetrics metrics = new Dedup4jMetrics(registry);

        metrics.recordUpload(10L, false);
        metrics.recordUpload(10L, true);

        assertThat(registry.get("dedup4j.uploads").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("dedup4j.duplicates").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dedup4j.skipped.physical.writes").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("dedup4j.bytes.accepted").counter().count()).isEqualTo(20.0);
        assertThat(registry.get("dedup4j.bytes.avoided").counter().count()).isEqualTo(10.0);
    }

    @Test
    void recordsOperationTimersFailuresAndRepairs() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Dedup4jMetrics metrics = new Dedup4jMetrics(registry);

        metrics.recordHashing(() -> "hash");
        metrics.recordStorageWrite(() -> "stored");
        metrics.recordDeleteFailure();
        metrics.recordRepair();

        assertThat(registry.get("dedup4j.hashing").timer().count()).isEqualTo(1L);
        assertThat(registry.get("dedup4j.storage.writes").timer().count()).isEqualTo(1L);
        assertThat(registry.get("dedup4j.storage.delete.failures").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("dedup4j.repairs").counter().count()).isEqualTo(1.0);
    }

    @Test
    void remainsOptionalWithoutARegistry() {
        Dedup4jMetrics metrics = new Dedup4jMetrics(null);

        assertThat(metrics.recordHashing(() -> "hash")).isEqualTo("hash");
        assertThat(metrics.recordStorageWrite(() -> "stored")).isEqualTo("stored");
        metrics.recordUpload(10L, true);
        metrics.recordDeleteFailure();
        metrics.recordRepair();
    }
}
