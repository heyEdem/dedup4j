package com.edem.blobhelper.autoconfigure;

import com.edem.blobhelper.autoconfigure.persistence.SchemaInitialization;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties(prefix = "blob-helper")
public class BlobHelperProperties {

    @NestedConfigurationProperty
    private final Storage storage = new Storage();

    @NestedConfigurationProperty
    private final Deduplication deduplication = new Deduplication();

    @NestedConfigurationProperty
    private final Cleanup cleanup = new Cleanup();

    @NestedConfigurationProperty
    private final Persistence persistence = new Persistence();

    public Storage getStorage() {
        return storage;
    }

    public Deduplication getDeduplication() {
        return deduplication;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public static class Persistence {

        private SchemaInitialization initializeSchema = SchemaInitialization.EMBEDDED;

        public SchemaInitialization getInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(SchemaInitialization initializeSchema) {
            this.initializeSchema = Objects.requireNonNull(initializeSchema,
                    "initializeSchema must not be null");
        }
    }

    public static class Storage {

        private String provider;
        private String keyPrefix = "";
        @NestedConfigurationProperty
        private final Local local = new Local();
        @NestedConfigurationProperty
        private final S3 s3 = new S3();
        @NestedConfigurationProperty
        private final Azure azure = new Azure();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Local getLocal() {
            return local;
        }

        public S3 getS3() {
            return s3;
        }

        public Azure getAzure() {
            return azure;
        }
    }

    public static class Local {

        private Path rootDirectory = Path.of("blob-helper-storage");

        public Path getRootDirectory() {
            return rootDirectory;
        }

        public void setRootDirectory(Path rootDirectory) {
            this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
        }
    }

    public static class S3 {

        private String bucket;
        private String region;
        private URI endpoint;
        private boolean pathStyle;

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }
    }

    public static class Azure {

        private String container;
        private String connectionString;
        private URI endpoint;
        private String accountName;

        public String getContainer() {
            return container;
        }

        public void setContainer(String container) {
            this.container = container;
        }

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }
    }

    public static class Deduplication {

        private String hashAlgorithm = "SHA-256";
        private DataSize maxUploadSize = DataSize.ofMegabytes(25);
        private boolean strictContentTypeValidation;

        public String getHashAlgorithm() {
            return hashAlgorithm;
        }

        public void setHashAlgorithm(String hashAlgorithm) {
            this.hashAlgorithm = hashAlgorithm;
        }

        public DataSize getMaxUploadSize() {
            return maxUploadSize;
        }

        public void setMaxUploadSize(DataSize maxUploadSize) {
            this.maxUploadSize = maxUploadSize;
        }

        public boolean isStrictContentTypeValidation() {
            return strictContentTypeValidation;
        }

        public void setStrictContentTypeValidation(boolean strictContentTypeValidation) {
            this.strictContentTypeValidation = strictContentTypeValidation;
        }
    }

    public static class Cleanup {

        private boolean deletePhysicalOnZeroReferences = true;
        private boolean reconciliationEnabled;

        public boolean isDeletePhysicalOnZeroReferences() {
            return deletePhysicalOnZeroReferences;
        }

        public void setDeletePhysicalOnZeroReferences(boolean deletePhysicalOnZeroReferences) {
            this.deletePhysicalOnZeroReferences = deletePhysicalOnZeroReferences;
        }

        public boolean isReconciliationEnabled() {
            return reconciliationEnabled;
        }

        public void setReconciliationEnabled(boolean reconciliationEnabled) {
            this.reconciliationEnabled = reconciliationEnabled;
        }
    }
}
