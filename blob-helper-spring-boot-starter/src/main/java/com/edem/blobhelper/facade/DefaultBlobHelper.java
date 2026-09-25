package com.edem.blobhelper.facade;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.blobhelper.service.BlobDeduplicationService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultBlobHelper implements BlobHelper {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobDeduplicationService service;
    private final BlobHelperProperties properties;

    public DefaultBlobHelper(BlobDeduplicationService service, BlobHelperProperties properties) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
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
        long maximum = properties.getDeduplication().getMaxUploadSize().toBytes();
        if (command.sizeBytes() > maximum) {
            closeAfterRejected(command.content());
            throw new BlobValidationException(
                    "Upload size " + command.sizeBytes() + " exceeds maximum " + maximum
            );
        }
        return service.store(command);
    }

    private static void closeAfterRejected(InputStream content) {
        try {
            content.close();
        } catch (IOException ignored) {
            // The size validation failure is the useful error for the caller.
        }
    }

    private static BlobValidationException sourceFailure(String source, IOException failure) {
        return new BlobValidationException("Could not open upload source " + source, failure);
    }
}
