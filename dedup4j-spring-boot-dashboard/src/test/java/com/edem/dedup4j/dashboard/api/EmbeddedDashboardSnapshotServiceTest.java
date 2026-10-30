package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.management.Dedup4jManagementProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDashboardSnapshotServiceTest {
    @Test
    void readsCountersAndIsSafeWhenOptionalCollaboratorsAreAbsent() {
        var registry = new SimpleMeterRegistry();
        registry.counter("dedup4j.uploads").increment(15);
        registry.counter("dedup4j.duplicates").increment(4);
        registry.counter("dedup4j.bytes.accepted").increment(1024);
        var service = new EmbeddedDashboardSnapshotService(single(MeterRegistry.class, registry), empty( com.edem.dedup4j.jpa.AssetContentRepository.class), empty(com.edem.dedup4j.history.UploadHistory.class), properties(), management());

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
        registry.counter("dedup4j.uploads").increment(2);
        registry.counter("dedup4j.duplicates").increment(5);
        var snapshot = new EmbeddedDashboardSnapshotService(single(MeterRegistry.class, registry), empty(com.edem.dedup4j.jpa.AssetContentRepository.class), empty(com.edem.dedup4j.history.UploadHistory.class), properties(), management()).current();
        assertThat(snapshot.newUploads()).isZero();
    }

    @Test
    void contentTotalsCountOnlyReferencedContent() throws Exception {
        var repository = repositoryOf(java.util.List.of(content(10, 1), content(32, 0)));
        var snapshot = new EmbeddedDashboardSnapshotService(empty(MeterRegistry.class),
                single(com.edem.dedup4j.jpa.AssetContentRepository.class, repository),
                empty(com.edem.dedup4j.history.UploadHistory.class), properties(), management()).current();

        assertThat(snapshot.contentCount()).isEqualTo(1);
        assertThat(snapshot.physicalBytes()).isEqualTo(10);
    }

    /** A repository whose findAll returns the given rows. */
    private static com.edem.dedup4j.jpa.AssetContentRepository repositoryOf(java.util.List<com.edem.dedup4j.jpa.AssetContent> rows) {
        Object query = java.lang.reflect.Proxy.newProxyInstance(jakarta.persistence.TypedQuery.class.getClassLoader(),
                new Class<?>[]{jakarta.persistence.TypedQuery.class},
                (proxy, method, args) -> method.getName().equals("getResultList") ? rows : proxy);
        jakarta.persistence.EntityManager entityManager = (jakarta.persistence.EntityManager) java.lang.reflect.Proxy.newProxyInstance(
                jakarta.persistence.EntityManager.class.getClassLoader(), new Class<?>[]{jakarta.persistence.EntityManager.class},
                (proxy, method, args) -> method.getName().equals("createQuery") ? query : null);
        return new com.edem.dedup4j.jpa.AssetContentRepository(entityManager);
    }

    private static com.edem.dedup4j.jpa.AssetContent content(long sizeBytes, long refCount) throws ReflectiveOperationException {
        var content = new com.edem.dedup4j.jpa.AssetContent("sha-256", "hash" + sizeBytes, sizeBytes, "key" + sizeBytes,
                "local", "root", null, null);
        java.lang.reflect.Field count = com.edem.dedup4j.jpa.AssetContent.class.getDeclaredField("refCount");
        count.setAccessible(true);
        count.setLong(content, refCount);
        return content;
    }

    private static Dedup4jProperties properties() { return new Dedup4jProperties(); }
    private static Dedup4jManagementProperties management() { return new Dedup4jManagementProperties(); }
    private static <T> org.springframework.beans.factory.ObjectProvider<T> single(Class<T> type, T value) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("value", value);
        return factory.getBeanProvider(type);
    }
    private static <T> org.springframework.beans.factory.ObjectProvider<T> empty(Class<T> type) {
        return new DefaultListableBeanFactory().getBeanProvider(type);
    }
}
