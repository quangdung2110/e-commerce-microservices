package com.ecommerce.review.integration;

import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.EligibilityStatus;
import com.ecommerce.review.messaging.OrderCompletedPayload;
import com.ecommerce.review.service.EntitlementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import com.ecommerce.review.dto.CommandDtos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real PostgreSQL, real Kafka and real S3-compatible storage.
 *
 * <p>An in-memory database would not exercise the composite foreign key, the partial
 * indexes, the enums, the partitioned outbox or the {@code updated_at} triggers — which
 * is most of what this design relies on ({@code docs/review/development.md 2}).
 *
 * <p>The containers are started once for the whole suite and reused; Spring's context
 * cache then keeps one application per configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractReviewIT {

    /**
     * A shared network with stable aliases, so a container that has to reach PostgreSQL
     * or Kafka from <em>inside</em> Docker can — which is what the CDC test needs of
     * Debezium. Tests that only talk to mapped host ports are unaffected.
     */
    static final Network NETWORK = Network.newNetwork();

    static final String POSTGRES_ALIAS = "review-postgres";
    static final String KAFKA_ALIAS = "review-kafka";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("review_service")
            .withUsername("postgres")
            .withPassword("postgres")
            .withNetwork(NETWORK)
            .withNetworkAliases(POSTGRES_ALIAS)
            // The outbox publication needs logical decoding, exactly as compose does.
            .withCommand("postgres", "-c", "wal_level=logical",
                    "-c", "max_replication_slots=4", "-c", "max_wal_senders=4");

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(NETWORK)
            .withNetworkAliases(KAFKA_ALIAS);

    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("ecommerce-minio:RELEASE.2025-10-15T17-29-55Z")
                    .asCompatibleSubstituteFor("minio/minio"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    static {
        POSTGRES.start();
        KAFKA.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Testcontainers already puts parameters on the URL, so the separator has to be
        // chosen rather than assumed: a second '?' silently drops currentSchema and every
        // unqualified relation then resolves against `public`.
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=review,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("review.storage.endpoint", MINIO::getS3URL);
        registry.add("review.storage.access-key", MINIO::getUserName);
        registry.add("review.storage.secret-key", MINIO::getPassword);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected S3Client s3;

    @Autowired
    protected EntitlementService entitlements;

    @Autowired
    protected ReviewProperties properties;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void prepareBucketAndData() {
        createBucketIfAbsent();
        truncateReviewTables();
    }

    private void createBucketIfAbsent() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.storage().bucket()).build());
        } catch (RuntimeException alreadyExists) {
            // Creating an existing bucket is not a failure; the suite reuses one container.
        }
    }

    /**
     * Each test starts from an empty schema.
     *
     * <p>{@code review_outbox} is truncated through its parent, which cascades to the
     * monthly partitions the bootstrap created.
     */
    protected void truncateReviewTables() {
        jdbc.sql("""
                TRUNCATE review_media, review_media_assets, review_reports, review_admin_replies,
                         review_takedowns, reviews, review_eligibilities, product_rating_aggregates,
                         review_event_receipts, privacy_erasure_requests, rating_export_sessions,
                         rating_export_session_offsets, review_outbox
                RESTART IDENTITY CASCADE
                """).update();
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** Grants an entitlement the way production does: through the Order consumer path. */
    protected UUID grantEntitlement(String orderId, String productId, String skuId, UUID buyer) {
        return grantEntitlement(orderId, productId, skuId, buyer, Instant.now(), "Đen / L");
    }

    protected UUID grantEntitlement(String orderId, String productId, String skuId, UUID buyer,
                                    Instant completedAt, String variantName) {
        return grantEntitlement(orderId, productId, skuId, buyer, completedAt, variantName, null, null);
    }

    protected UUID grantEntitlement(String orderId, String productId, String skuId, UUID buyer,
                                    Instant completedAt, String variantName,
                                    String productName, String productImageUrl) {
        entitlements.apply(UUID.randomUUID(),
                new OrderCompletedPayload(orderId, buyer, completedAt,
                        List.of(new OrderCompletedPayload.Item(productId, skuId, variantName,
                                productName, productImageUrl, 1))),
                properties.topics().order(), 0, nextOffset());
        return jdbc.sql("""
                SELECT id FROM review_eligibilities
                 WHERE order_id = ? AND product_id = ? AND sku_id = ?
                """).param(orderId).param(productId).param(skuId)
                .query(UUID.class).single();
    }

    /**
     * Uploads through the signed instruction Review actually issued.
     *
     * <p>Going through the presigned URL rather than an admin client is the point: it is
     * what proves the signature Review hands out is usable for exactly this object, with
     * exactly the headers it pinned.
     */
    protected void upload(CommandDtos.IssuedMediaAsset issued, byte[] bytes) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(issued.upload().url()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            issued.upload().headers().forEach((name, value) -> {
                // Content-Length is set by the body publisher and is restricted for clients.
                if (!"Content-Length".equalsIgnoreCase(name)) {
                    request.header(name, value);
                }
            });
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("signed upload failed with HTTP " + response.statusCode()
                        + ": " + response.body());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("signed upload failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("signed upload interrupted", e);
        }
    }

    protected EligibilityStatus eligibilityStatus(UUID eligibilityId) {
        return EligibilityStatus.valueOf(jdbc.sql("SELECT status FROM review_eligibilities WHERE id = ?")
                .param(eligibilityId).query(String.class).single());
    }

    protected List<String> outboxTypes() {
        return jdbc.sql("SELECT event_type FROM review_outbox ORDER BY created_at, event_type")
                .query(String.class).list();
    }

    protected List<JsonNode> outboxPayloads(String eventType) {
        return jdbc.sql("SELECT payload FROM review_outbox WHERE event_type = ? ORDER BY created_at")
                .param(eventType)
                .query(String.class).list().stream()
                .map(this::readTree)
                .toList();
    }

    protected long outboxCount(String eventType) {
        return jdbc.sql("SELECT count(*) FROM review_outbox WHERE event_type = ?")
                .param(eventType).query(Long.class).single();
    }

    protected JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("stored payload is not JSON", e);
        }
    }

    protected static Duration days(int value) {
        return Duration.ofDays(value);
    }

    /**
     * Synthetic offsets start far above any real Kafka position.
     *
     * <p>Tests that drive a consumer service directly supply their own
     * {@code (topic, partition, offset)}, and the rating aggregator also consumes
     * {@code t.review.events} for real once CDC is running. Both write receipts under the
     * same consumer name, and {@code uq_event_receipt_source_position} rightly refuses two
     * different events at one log position — so the fixture has to stay out of the range
     * the broker actually uses.
     */
    private static long offsetCounter = 1_000_000_000L;

    protected static synchronized long nextOffset() {
        return offsetCounter++;
    }
}
