package com.edem.dedup4j.facade;

import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlobStoreMvcUsageTest {

    @Test
    void bindsMultipartArrayWithoutLibraryController() throws Exception {
        CapturingFacade facade = new CapturingFacade();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestUploadController(facade)).build();

        mvc.perform(multipart("/uploads")
                        .file(new MockMultipartFile("files", "first.txt", "text/plain", new byte[] {1}))
                        .file(new MockMultipartFile("files", "second.txt", "text/plain", new byte[] {2}))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));

        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("first.txt", "second.txt"),
                List.of(facade.files[0].getOriginalFilename(), facade.files[1].getOriginalFilename())
        );
    }

    @RestController
    static final class TestUploadController {
        private final BlobStore helper;

        TestUploadController(BlobStore helper) {
            this.helper = helper;
        }

        @PostMapping("/uploads")
        String upload(@RequestPart("files") MultipartFile[] files) {
            return Integer.toString(helper.storeAll(files).outcomes().size());
        }
    }

    private static final class CapturingFacade implements BlobStore {
        private MultipartFile[] files;

        @Override public BlobReference store(MultipartFile file) { return reference(false); }
        @Override public BlobReference store(Path path) { return reference(false); }
        @Override public BlobReference store(byte[] content, String filename, String contentType) { return reference(false); }
        @Override public BlobReference store(InputStream content, long sizeBytes, String filename, String contentType, Map<String, String> metadata) { return reference(false); }

        @Override
        public BatchStoreResult storeAll(MultipartFile[] files) {
            this.files = files;
            return new BatchStoreResult(List.of(
                    new BlobStoreSuccess(0, files[0].getOriginalFilename(), reference(false)),
                    new BlobStoreSuccess(1, files[1].getOriginalFilename(), reference(true))
            ));
        }

        private static BlobReference reference(boolean duplicate) {
            return new BlobReference(
                    UUID.randomUUID(), new ContentHash("sha-256", "abc", 3),
                    "text/plain", "local", "bucket", "key", duplicate
            );
        }
    }
}
