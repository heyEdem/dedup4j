package com.edem.dedup4j.facade;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.service.BlobDeduplicationService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultBlobStore implements BlobStore {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobDeduplicationService service;

    /**
     * Adapts uploads into {@link StoreBlobCommand}s for {@code service}, which enforces upload
     * limits such as the maximum size and allowed content types.
     */
    public DefaultBlobStore(BlobDeduplicationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    /**
     * @deprecated since 0.2.0; {@code properties} is ignored because the service enforces upload
     * limits. Use {@link #DefaultBlobStore(BlobDeduplicationService)}.
     */
    @Deprecated(since = "0.2.0", forRemoval = true)
    public DefaultBlobStore(BlobDeduplicationService service, Dedup4jProperties properties) {
        this(service);
        Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public BlobReference store(MultipartFile file) {
        Objects.requireNonNull(file, "file must not be null");
        try {
            return store(new StoreBlobCommand(
                    file.getInputStream(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    Map.of()
            ));
        } catch (IOException failure) {
            throw sourceFailure(file.getOriginalFilename(), failure);
        }
    }

    @Override
    public BlobReference store(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            long sizeBytes = Files.size(path);
            String contentType = Files.probeContentType(path);
            return store(new StoreBlobCommand(
                    Files.newInputStream(path),
                    path.getFileName() == null ? null : path.getFileName().toString(),
                    contentType == null ? DEFAULT_CONTENT_TYPE : contentType,
                    sizeBytes,
                    Map.of()
            ));
        } catch (IOException failure) {
            throw sourceFailure(path.toString(), failure);
        }
    }

    @Override
    public BlobReference store(byte[] content, String filename, String contentType) {
        if (content == null) {
            throw new BlobValidationException("content must not be null");
        }
        return store(new StoreBlobCommand(
                new java.io.ByteArrayInputStream(content),
                filename,
                contentType,
                content.length,
                Map.of()
        ));
    }

    @Override
    public BlobReference store(
            InputStream content,
            long sizeBytes,
            String filename,
            String contentType,
            Map<String, String> metadata
    ) {
        return store(new StoreBlobCommand(content, filename, contentType, sizeBytes, metadata));
    }

    @Override
    public BatchStoreResult storeAll(MultipartFile[] files) {
        if (files == null) {
            throw new BlobValidationException("files must not be null");
        }
        List<BatchStoreOutcome> outcomes = new ArrayList<>(files.length);
        for (int index = 0; index < files.length; index++) {
            MultipartFile file = files[index];
            String filename = null;
            try {
                if (file == null) {
                    throw new BlobValidationException("file must not be null");
                }
                filename = file.getOriginalFilename();
                outcomes.add(new BlobStoreSuccess(index, filename, store(file)));
            } catch (RuntimeException failure) {
                outcomes.add(new BlobStoreFailure(index, filename, failure));
            }
        }
        return new BatchStoreResult(outcomes);
    }

    private BlobReference store(StoreBlobCommand command) {
        return service.store(command);
    }

    private static BlobValidationException sourceFailure(String source, IOException failure) {
        return new BlobValidationException("Could not open upload source " + source, failure);
    }
}
