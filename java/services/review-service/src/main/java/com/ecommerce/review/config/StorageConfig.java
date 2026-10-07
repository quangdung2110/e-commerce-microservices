package com.ecommerce.review.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * The S3-compatible client and presigner.
 *
 * <p>Path-style addressing is the default because MinIO and most self-hosted S3 servers
 * do not serve virtual-host style buckets; a managed provider can turn it off through
 * {@code REVIEW_S3_PATH_STYLE}.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(ReviewProperties properties) {
        ReviewProperties.Storage storage = properties.storage();
        return S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.pathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(ReviewProperties properties) {
        ReviewProperties.Storage storage = properties.storage();
        return S3Presigner.builder()
                .endpointOverride(URI.create(storage.signingEndpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.pathStyleAccess())
                        .build())
                .build();
    }
}
