package com.edem.blobhelper.service;

import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.DuplicateContentIdentityException;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringTransactionalBlobDeduplicationServiceTest {

    @Test
    void retriesDuplicateInFreshTransactionWithoutReplayingStream() {
        RecordingTransactions transactions = new RecordingTransactions();
        UUID id = UUID.randomUUID();
        AssetContent winner = new AssetContent(
                "sha-256", "abc", 3, "sha-256/ab/abc", "local", "bucket", "text/plain", "txt"
        );
        setId(winner, id);
        AssetContentRepository repository = new AssetContentRepository(entityManagerReturning(winner, transactions.events));
        ReferenceCountService references = new ReferenceCountService(repository, nullStorage());
        AtomicInteger delegateCalls = new AtomicInteger();
        BlobDeduplicationService delegate = new BlobDeduplicationService() {
            @Override
            public BlobReference store(StoreBlobCommand command) {
                delegateCalls.incrementAndGet();
                transactions.events.add("delegate");
                try {
                    command.content().readNBytes(3);
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
                throw new DuplicateContentIdentityException(
                        new AssetContent("sha-256", "abc", 3, "ignored", "local", "bucket", "text/plain", "txt"),
                        new IllegalStateException("unique race")
                );
            }

            @Override public void retain(UUID ignored) { throw new AssertionError("delegate retain called"); }
            @Override public void release(UUID ignored) { throw new AssertionError("delegate release called"); }
            @Override public BlobResource get(UUID ignored) { throw new AssertionError("delegate get called"); }
            @Override public BlobLocation location(UUID ignored) { throw new AssertionError("delegate location called"); }
        };
        SpringTransactionalBlobDeduplicationService service = new SpringTransactionalBlobDeduplicationService(
                delegate, repository, references, transactions
        );
        StoreBlobCommand command = new StoreBlobCommand(
                new OneShotInputStream(new byte[] {1, 2, 3}), "a.txt", "text/plain", 3, Map.of()
        );

        BlobReference result = service.store(command);

        assertEquals(2, transactions.beginCount.get());
        assertEquals(1, transactions.rollbackCount.get());
        assertEquals(1, transactions.commitCount.get());
        assertEquals(2, transactions.propagations.size());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, transactions.propagations.get(0));
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, transactions.propagations.get(1));
        assertEquals(1, delegateCalls.get());
        assertEquals(2L, winner.getRefCount());
        assertSame(id, result.assetContentId());
        assertEquals("abc", result.contentHash().hash());
        org.junit.jupiter.api.Assertions.assertTrue(result.duplicate());
        assertEquals(java.util.List.of("begin", "delegate", "rollback", "begin", "reload", "retain", "commit"), transactions.events);
    }

    @Test
    void propagatesRaceWhenWinningRowCannotBeLoaded() {
        RecordingTransactions transactions = new RecordingTransactions();
        ThrowingDelegate delegate = new ThrowingDelegate(transactions.events);
        AssetContentRepository repository = new AssetContentRepository(entityManagerReturning(null, transactions.events));
        SpringTransactionalBlobDeduplicationService service = new SpringTransactionalBlobDeduplicationService(
                delegate, repository, new ReferenceCountService(repository, nullStorage()), transactions
        );
        DuplicateContentIdentityException failure = assertThrows(
                DuplicateContentIdentityException.class,
                () -> service.store(new StoreBlobCommand(
                        new ByteArrayInputStream(new byte[] {1}), "a", "text/plain", 1, Map.of()
                ))
        );

        assertSame(delegate.failure, failure);
        assertEquals(2, transactions.beginCount.get());
        assertEquals(2, transactions.rollbackCount.get());
        assertEquals(java.util.List.of("begin", "delegate", "rollback", "begin", "reload", "rollback"), transactions.events);
    }

    @Test
    void wrapsEveryPublicOperationInRequiresNew() {
        RecordingTransactions transactions = new RecordingTransactions();
        AtomicInteger calls = new AtomicInteger();
        BlobDeduplicationService delegate = new BlobDeduplicationService() {
            @Override public BlobReference store(StoreBlobCommand command) {
                calls.incrementAndGet();
                return new BlobReference(UUID.randomUUID(), new com.edem.dedup4j.core.hash.ContentHash("sha-256", "a", 0), "text/plain", "test", "bucket", "key", false);
            }
            @Override public void retain(UUID id) { calls.incrementAndGet(); }
            @Override public void release(UUID id) { calls.incrementAndGet(); }
            @Override public BlobResource get(UUID id) {
                calls.incrementAndGet();
                return new BlobResource("key", new ByteArrayInputStream(new byte[0]), 0, "application/octet-stream", Map.of());
            }
            @Override public BlobLocation location(UUID id) { calls.incrementAndGet(); return new BlobLocation("test", "bucket", "key"); }
        };
        AssetContentRepository repository = new AssetContentRepository(entityManagerReturning(null, transactions.events));
        SpringTransactionalBlobDeduplicationService service = new SpringTransactionalBlobDeduplicationService(
                delegate, repository, new ReferenceCountService(repository, nullStorage()), transactions
        );

        UUID id = UUID.randomUUID();
        service.retain(id);
        service.release(id);
        service.get(id);
        service.location(id);
        service.store(new StoreBlobCommand(new ByteArrayInputStream(new byte[0]), "a", "text/plain", 0, Map.of()));

        assertEquals(5, calls.get());
        assertEquals(5, transactions.beginCount.get());
        assertEquals(5, transactions.commitCount.get());
        assertEquals(0, transactions.rollbackCount.get());
        assertEquals(5, transactions.propagations.stream()
                .filter(value -> value == TransactionDefinition.PROPAGATION_REQUIRES_NEW).count());
    }

    private static final class ThrowingDelegate implements BlobDeduplicationService {
        private final java.util.List<String> events;
        private DuplicateContentIdentityException failure;
        private ThrowingDelegate(java.util.List<String> events) {
            this.events = events;
        }
        @Override public BlobReference store(StoreBlobCommand command) {
            events.add("delegate");
            failure = new DuplicateContentIdentityException(
                    new AssetContent("sha-256", "abc", 3, "ignored", "local", "bucket", "text/plain", "txt"),
                    new IllegalStateException("unique race")
            );
            throw failure;
        }
        @Override public void retain(UUID id) { throw new AssertionError(); }
        @Override public void release(UUID id) { throw new AssertionError(); }
        @Override public BlobResource get(UUID id) { throw new AssertionError(); }
        @Override public BlobLocation location(UUID id) { throw new AssertionError(); }
    }

    private static EntityManager entityManagerReturning(AssetContent winner, java.util.List<String> events) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(), new Class<?>[] {EntityManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("find")) { events.add("retain"); return winner; }
                    if (method.getName().equals("toString")) return "test-entity-manager";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("isOpen")) return true;
                    if (method.getName().equals("createQuery")) { events.add("reload"); return queryReturning(winner); }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object queryReturning(AssetContent winner) {
        return Proxy.newProxyInstance(
                jakarta.persistence.TypedQuery.class.getClassLoader(),
                new Class<?>[] {jakarta.persistence.TypedQuery.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setParameter")) return proxy;
                    if (method.getName().equals("getResultStream")) {
                        return winner == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(winner);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static final class OneShotInputStream extends java.io.InputStream {
        private final byte[] bytes;
        private int position;
        private boolean eofReturned;

        private OneShotInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            if (eofReturned) {
                throw new AssertionError("upload stream was replayed");
            }
            if (position == bytes.length) {
                eofReturned = true;
                return -1;
            }
            return bytes[position++];
        }
    }

    private static com.edem.dedup4j.core.storage.BlobStorage nullStorage() {
        return new com.edem.dedup4j.core.storage.BlobStorage() {
            @Override public com.edem.dedup4j.core.storage.StoredBlob put(com.edem.dedup4j.core.storage.PutBlobRequest request) { throw new AssertionError(); }
            @Override public BlobResource get(String objectKey) { throw new AssertionError(); }
            @Override public void delete(String objectKey) { }
            @Override public boolean exists(String objectKey) { return false; }
        };
    }

    private static void setId(AssetContent content, UUID id) {
        try {
            var field = AssetContent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(content, id);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        private final AtomicInteger beginCount = new AtomicInteger();
        private final AtomicInteger commitCount = new AtomicInteger();
        private final AtomicInteger rollbackCount = new AtomicInteger();
        private final java.util.List<Integer> propagations = new java.util.ArrayList<>();
        private final java.util.List<String> events = new java.util.ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            beginCount.incrementAndGet();
            events.add("begin");
            propagations.add(definition.getPropagationBehavior());
            return new DefaultTransactionStatus("test", new Object(), true, false, false, false, false, null);
        }
        @Override public void commit(TransactionStatus status) { commitCount.incrementAndGet(); events.add("commit"); }
        @Override public void rollback(TransactionStatus status) { rollbackCount.incrementAndGet(); events.add("rollback"); }
    }
}
