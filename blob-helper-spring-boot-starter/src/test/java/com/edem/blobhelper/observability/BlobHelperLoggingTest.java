package com.edem.blobhelper.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.blobhelper.jpa.AssetContentMutationService;
import com.edem.blobhelper.jpa.AssetContentRepository;
import com.edem.blobhelper.jpa.ReferenceCountService;
import com.edem.blobhelper.service.DefaultBlobDeduplicationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobHelperLoggingTest {

    private static final byte[] CONTENT = "logging-content".getBytes(StandardCharsets.UTF_8);
    private static final Logger SERVICE_LOGGER =
            (Logger) LoggerFactory.getLogger(DefaultBlobDeduplicationService.class);
    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private RecordingBlobStorage storage;
    private DefaultBlobDeduplicationService service;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("blob-helper-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void setUp() {
        entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        entityManager.createQuery("delete from AssetContent").executeUpdate();

        storage = new RecordingBlobStorage();
        AssetContentRepository repository = new AssetContentRepository(entityManager);
        ContentHasher hasher = new Sha256ContentHasher();
        service = new DefaultBlobDeduplicationService(
                repository,
                new ReferenceCountService(repository, storage),
                new AssetContentMutationService(entityManager),
                storage,
                hasher,
                new HashObjectKeyStrategy("")
        );

        logAppender = new ListAppender<>();
        logAppender.start();
        SERVICE_LOGGER.addAppender(logAppender);
        SERVICE_LOGGER.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        SERVICE_LOGGER.detachAppender(logAppender);
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void logsHashPrefixOnlyByDefault() {
        BlobReference reference = service.store(command("first.txt"));
        BlobReference duplicate = service.store(command("copy.txt"));

        List<String> uploadMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("event=blob.upload"))
                .toList();

        assertThat(uploadMessages).hasSize(2);
        assertThat(uploadMessages.get(0))
                .contains("contentId=" + reference.assetContentId())
                .contains("provider=recording")
                .contains("objectKey=" + reference.objectKey())
                .contains("decision=new")
                .contains("hashPrefix=" + reference.contentHash().hash().substring(0, 8));
        assertThat(uploadMessages.get(1))
                .contains("contentId=" + duplicate.assetContentId())
                .contains("decision=duplicate")
                .contains("hashPrefix=" + duplicate.contentHash().hash().substring(0, 8));
        assertThat(uploadMessages)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("contentHash=" + reference.contentHash().hash()));
    }

    @Test
    void logsFailedPhysicalDeleteWithReconciliationContext() {
        BlobReference reference = service.store(command("delete-failure.txt"));
        storage.failDeletes.set(true);

        assertThatThrownBy(() -> service.release(reference.assetContentId()))
                .isInstanceOf(BlobStorageException.class);

        ILoggingEvent failure = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("event=blob.delete.failed"))
                .findFirst()
                .orElseThrow();

        assertThat(failure.getLevel()).isEqualTo(Level.ERROR);
        assertThat(failure.getFormattedMessage())
                .contains("contentId=" + reference.assetContentId())
                .contains("provider=recording")
                .contains("objectKey=" + reference.objectKey())
                .contains("errorType=" + BlobStorageException.class.getSimpleName());
    }

    private static StoreBlobCommand command(String filename) {
        return new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                filename,
                "text/plain",
                CONTENT.length,
                Map.of("source", "logging-test")
        );
    }

    private static final class RecordingBlobStorage implements BlobStorage {

        private final AtomicBoolean failDeletes = new AtomicBoolean();

        @Override
        public StoredBlob put(PutBlobRequest request) {
            return new StoredBlob(
                    request.objectKey(),
                    "recording",
                    "test-bucket",
                    request.sizeBytes(),
                    request.contentType(),
                    null,
                    Instant.now()
            );
        }

        @Override
        public BlobResource get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            if (failDeletes.get()) {
                throw new BlobStorageException("delete failed for " + objectKey);
            }
        }

        @Override
        public boolean exists(String objectKey) {
            return false;
        }
    }
}
