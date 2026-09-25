package com.edem.blobhelper.autoconfigure.storage;

import com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration;
import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.storage.s3.S3BlobStorage;
import com.edem.dedup4j.storage.s3.S3BlobStorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "s3")
@ConditionalOnMissingBean(BlobStorage.class)
@EnableConfigurationProperties(BlobHelperProperties.class)
public class S3BlobStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    S3BlobStorageProperties s3BlobStorageProperties(BlobHelperProperties properties) {
        BlobHelperProperties.S3 source = properties.getStorage().getS3();
        if (source.getBucket() == null || source.getBucket().isBlank()) {
            throw new IllegalStateException("blob-helper.storage.s3.bucket is required when provider=s3");
        }
        S3BlobStorageProperties target = new S3BlobStorageProperties();
        target.setBucket(source.getBucket());
        target.setRegion(source.getRegion());
        target.setEndpointOverride(source.getEndpoint());
        target.setPathStyleAccess(source.isPathStyle());
        return target;
    }

    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    S3Client blobHelperS3Client(S3BlobStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder().forcePathStyle(properties.isPathStyleAccess());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (properties.getEndpointOverride() != null) {
            builder.endpointOverride(properties.getEndpointOverride());
        }
        return builder.build();
    }

    @Bean(name = "s3BlobStorage", destroyMethod = "")
    @ConditionalOnMissingBean(BlobStorage.class)
    S3BlobStorage s3BlobStorage(S3Client client, S3BlobStorageProperties properties) {
        return new S3BlobStorage(client, properties);
    }
}
