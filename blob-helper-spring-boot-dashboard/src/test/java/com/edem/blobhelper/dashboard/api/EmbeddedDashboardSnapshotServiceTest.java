package com.edem.blobhelper.dashboard.api;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.blobhelper.management.BlobHelperManagementProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDashboardSnapshotServiceTest {
    @Test
    void readsCountersAndIsSafeWhenOptionalCollaboratorsAreAbsent() {
        var registry = new SimpleMeterRegistry();
        registry.counter("blob.helper.uploads").increment(15);
        registry.counter("blob.helper.duplicates").increment(4);
        registry.counter("blob.helper.bytes.accepted").increment(1024);
        var service = new EmbeddedDashboardSnapshotService(single(MeterRegistry.class, registry), empty( com.edem.dedup4j.jpa.AssetContentRepository.class), properties(), management());

        var snapshot = service.current();

        assertThat(snapshot.uploads()).isEqualTo(15);
        assertThat(snapshot.duplicates()).isEqualTo(4);
        assertThat(snapshot.newUploads()).isEqualTo(11);
        assertThat(snapshot.logicalBytes()).isEqualTo(1024);
        assertThat(snapshot.contentCount()).isZero();
    }

    @Test
    void neverReportsNegativeNewUploads() {
        var registry = new SimpleMeterRegistry();
        registry.counter("blob.helper.uploads").increment(2);
        registry.counter("blob.helper.duplicates").increment(5);
        var snapshot = new EmbeddedDashboardSnapshotService(single(MeterRegistry.class, registry), empty(com.edem.dedup4j.jpa.AssetContentRepository.class), properties(), management()).current();
        assertThat(snapshot.newUploads()).isZero();
    }

    private static BlobHelperProperties properties() { return new BlobHelperProperties(); }
    private static BlobHelperManagementProperties management() { return new BlobHelperManagementProperties(); }
    private static <T> org.springframework.beans.factory.ObjectProvider<T> single(Class<T> type, T value) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("value", value);
        return factory.getBeanProvider(type);
    }
    private static <T> org.springframework.beans.factory.ObjectProvider<T> empty(Class<T> type) {
        return new DefaultListableBeanFactory().getBeanProvider(type);
    }
}
