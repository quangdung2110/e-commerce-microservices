package com.ecommerce.review.config;

import com.ecommerce.review.support.TestProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigTest {
    @Test
    void publicEndpointIsSignedWhileInternalClientKeepsThePrivateOrigin() {
        var defaults = TestProperties.defaults();
        var storage = new ReviewProperties.Storage("http://minio:9000", "http://localhost:9000",
                "us-east-1", "review-media", "key", "secret", true);
        var properties = new ReviewProperties(defaults.windowDays(), defaults.maxContentLength(),
                defaults.maxMediaPerReview(), defaults.reportThreshold(), defaults.exportSessionTtl(),
                defaults.eventReceiptRetentionDays(), defaults.outboxRetentionDays(), defaults.secrets(), defaults.media(),
                storage, defaults.gateway(), defaults.internal(), defaults.topics(), defaults.consumerGroups(), defaults.scheduler());
        var config = new StorageConfig();
        try (var client = config.s3Client(properties); var presigner = config.s3Presigner(properties)) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).hasValue(java.net.URI.create("http://minio:9000"));
            var result = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder().bucket("review-media").key("reviews/photo.jpg").build())
                    .build());
            assertThat(result.url().toString()).startsWith("http://localhost:9000/review-media/reviews/photo.jpg?")
                    .contains("X-Amz-Signature=");
        }
    }

    @Test
    void missingPublicEndpointFallsBackToInternalOrigin() {
        assertThat(new ReviewProperties.Storage("http://minio:9000", "", "us-east-1", "bucket", "key", "secret", true)
                .signingEndpoint()).isEqualTo("http://minio:9000");
    }
}
