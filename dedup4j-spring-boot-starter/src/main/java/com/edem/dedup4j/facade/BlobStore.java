package com.edem.dedup4j.facade;

import com.edem.dedup4j.core.model.BlobReference;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

public interface BlobStore {

    BlobReference store(MultipartFile file);

    BlobReference store(Path path);

    BlobReference store(byte[] content, String filename, String contentType);

    BlobReference store(
            InputStream content,
            long sizeBytes,
            String filename,
            String contentType,
            Map<String, String> metadata
    );

    BatchStoreResult storeAll(MultipartFile[] files);
}
