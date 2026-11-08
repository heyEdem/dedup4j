package com.edem.dedup4j.management;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.reconcile.ContentReferenceCountSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentCatalogTest {

    private final List<String> queries = new ArrayList<>();
    private final List<Integer> offsets = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();

    @Test
    void pageListsReferencedContentWithClampedSize() {
        AssetContent shared = content("aa", 3);
        ContentCatalog catalog = catalog(List.of(shared), null);

        Dedup4jManagementSnapshot.ContentPage page = catalog.page("size", 2, 500);

        assertThat(page.items()).containsExactly(new Dedup4jManagementSnapshot.Content(
                shared.getId(), "sha-256", "aa", 10, "image/png", 3));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.total()).isEqualTo(1);
        assertThat(offsets).containsExactly(400);
        assertThat(limits).containsExactly(200);
        assertThat(queries.getFirst()).contains("refCount > 0").contains("sizeBytes desc");
    }

    @Test
    void pageDefaultsToMostReferencedFirst() {
        ContentCatalog catalog = catalog(List.of(), null);

        Dedup4jManagementSnapshot.ContentPage page = catalog.page(null, null, null);

        assertThat(page.size()).isEqualTo(20);
        assertThat(queries.getFirst()).contains("refCount desc");
    }

    @Test
    void invalidSortOrPageIsABadRequest() {
        ContentCatalog catalog = catalog(List.of(), null);

        for (Runnable call : List.<Runnable>of(
                () -> catalog.page("name", 0, 10),
                () -> catalog.page(null, -1, 10),
                () -> catalog.page(null, Integer.MAX_VALUE, 200))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void detailAddsTheApplicationsLogicalCountWhenAvailable() {
        AssetContent released = content("bb", 0);
        ContentReferenceCountSource source = id -> id.equals(released.getId()) ? 4 : 0;

        assertThat(catalog(List.of(released), source).find(released.getId()).orElseThrow().logicalReferences())
                .isEqualTo(4);
        assertThat(catalog(List.of(released), null).find(released.getId()).orElseThrow())
                .satisfies(detail -> {
                    assertThat(detail.logicalReferences()).isNull();
                    assertThat(detail.refCount()).isZero();
                    assertThat(detail.objectKey()).isEqualTo("key-bb");
                });
        assertThat(catalog(List.of(), source).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void withoutPersistenceThePageIsEmptyAndRowsAreNotFound() {
        DefaultListableBeanFactory empty = new DefaultListableBeanFactory();
        ContentCatalog catalog = new ContentCatalog(empty.getBeanProvider(AssetContentRepository.class),
                empty.getBeanProvider(ContentReferenceCountSource.class),
                empty.getBeanProvider(PlatformTransactionManager.class));

        assertThat(catalog.page(null, 0, 10).items()).isEmpty();
        assertThat(catalog.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void managementReportsAMissingRowAsNotFound() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("contents", repository(List.of()));
        Dedup4jManagementController controller = new Dedup4jManagementController(new Dedup4jManagementProperties(),
                new Dedup4jProperties(), beans.getBeanProvider(UploadHistory.class),
                beans.getBeanProvider(AssetContentRepository.class),
                beans.getBeanProvider(Dedup4jManagementSnapshot.FailureSource.class));

        assertThatThrownBy(() -> controller.content(UUID.randomUUID())).isInstanceOfSatisfying(
                ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void detailReadsTheApplicationsCountInsideAReadOnlyTransaction() {
        AssetContent shared = content("cc", 2);
        List<Boolean> readOnly = new ArrayList<>();
        ContentReferenceCountSource source = id -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            readOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return 2;
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("transactionManager", new NoOpTransactionManager());

        assertThat(catalog(List.of(shared), source, beans).find(shared.getId()).orElseThrow().logicalReferences())
                .isEqualTo(2);
        assertThat(readOnly).containsExactly(true);
    }

    private ContentCatalog catalog(List<AssetContent> rows, ContentReferenceCountSource source) {
        return catalog(rows, source, new DefaultListableBeanFactory());
    }

    private ContentCatalog catalog(List<AssetContent> rows, ContentReferenceCountSource source,
                                   DefaultListableBeanFactory beans) {
        beans.registerSingleton("contents", repository(rows));
        if (source != null) {
            beans.registerSingleton("references", source);
        }
        ObjectProvider<ContentReferenceCountSource> references = beans.getBeanProvider(ContentReferenceCountSource.class);
        return new ContentCatalog(beans.getBeanProvider(AssetContentRepository.class), references,
                beans.getBeanProvider(PlatformTransactionManager.class));
    }

    /** A repository over a fixed result list that records each query, offset and limit. */
    private AssetContentRepository repository(List<AssetContent> rows) {
        Object query = Proxy.newProxyInstance(TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResultList" -> rows;
                    case "getSingleResult" -> (long) rows.size();
                    case "setFirstResult" -> { offsets.add((Integer) args[0]); yield proxy; }
                    case "setMaxResults" -> { limits.add((Integer) args[0]); yield proxy; }
                    default -> proxy;
                });
        EntityManager entityManager = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createQuery" -> { queries.add((String) args[0]); yield query; }
                    case "find" -> rows.stream().filter(row -> row.getId().equals(args[1])).findFirst().orElse(null);
                    default -> null;
                });
        return new AssetContentRepository(entityManager);
    }

    private static AssetContent content(String hash, long refCount) {
        AssetContent content = new AssetContent("sha-256", hash, 10, "key-" + hash, "local", "root", "image/png",
                "png");
        try {
            var id = AssetContent.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(content, UUID.randomUUID());
            var count = AssetContent.class.getDeclaredField("refCount");
            count.setAccessible(true);
            count.setLong(content, refCount);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return content;
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
