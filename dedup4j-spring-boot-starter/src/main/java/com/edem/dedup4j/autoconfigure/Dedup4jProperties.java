package com.edem.dedup4j.autoconfigure;

import com.edem.dedup4j.autoconfigure.persistence.SchemaInitialization;
import com.edem.dedup4j.reconcile.ReconciliationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties(prefix = "dedup4j")
public class Dedup4jProperties {

    @NestedConfigurationProperty
    private final Storage storage = new Storage();

    @NestedConfigurationProperty
    private final Deduplication deduplication = new Deduplication();


    @NestedConfigurationProperty
    private final Persistence persistence = new Persistence();

    @NestedConfigurationProperty
    private final History history = new History();

    @NestedConfigurationProperty
    private final Reconciliation reconciliation = new Reconciliation();

    @NestedConfigurationProperty
    private final Cleanup cleanup = new Cleanup();

    public Storage getStorage() {
        return storage;
    }

    public Deduplication getDeduplication() {
        return deduplication;
    }


    public Persistence getPersistence() {
        return persistence;
    }

    public History getHistory() {
        return history;
    }

    public Reconciliation getReconciliation() {
        return reconciliation;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    /**
     * Scheduled storage cleanup: objects left by released content and, when a key prefix is set,
     * objects no content row points to.
     */
    public static class Cleanup {

        private boolean enabled = true;
        private String cron = "0 30 3 * * *";
        /** Time zone for the cron expression; the JVM default when unset. */
        private String zone;
        /** Objects written more recently than this are left for a later run by the orphan sweep. */
        private Duration orphanGracePeriod = Duration.ofHours(24);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public Duration getOrphanGracePeriod() {
            return orphanGracePeriod;
        }

        public void setOrphanGracePeriod(Duration orphanGracePeriod) {
            this.orphanGracePeriod = Objects.requireNonNull(orphanGracePeriod, "orphanGracePeriod must not be null");
        }
    }

    /**
     * Scheduled, read-only reference-count reconciliation. Enable it on one instance per shared
     * database; instances are not coordinated.
     */
    public static class Reconciliation {

        private boolean enabled;
        private String cron = "0 0 3 * * *";
        /** Time zone for the cron expression; the JVM default when unset. */
        private String zone;
        private ReconciliationMode mode = ReconciliationMode.REPORT;
        /** Maximum mismatches and dangling references kept in a run's result and logged. */
        private int sampleSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public ReconciliationMode getMode() {
            return mode;
        }

        public void setMode(ReconciliationMode mode) {
            this.mode = mode;
        }

        public int getSampleSize() {
            return sampleSize;
        }

        public void setSampleSize(int sampleSize) {
            this.sampleSize = sampleSize;
        }
    }

    public static class History {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
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

        private Path rootDirectory = Path.of("dedup4j-storage");

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

        private DataSize maxUploadSize = DataSize.ofMegabytes(25);

        /** Allowed declared content types ({@code type/subtype} or {@code type/*}); empty allows all. */
        private List<String> allowedContentTypes = new ArrayList<>();

        public DataSize getMaxUploadSize() {
            return maxUploadSize;
        }

        public void setMaxUploadSize(DataSize maxUploadSize) {
            this.maxUploadSize = maxUploadSize;
        }

        public List<String> getAllowedContentTypes() {
            return allowedContentTypes;
        }

        public void setAllowedContentTypes(List<String> allowedContentTypes) {
            this.allowedContentTypes = allowedContentTypes;
        }
    }

}
