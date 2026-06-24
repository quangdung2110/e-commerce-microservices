package com.ecommerce.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ecommerce.search.common.EventType;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.config.TestSearchConfig;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;

/**
 * Abstract base class for Search Service integration tests.
 *
 * <p>Provides shared {@link ElasticsearchContainer} and {@link KafkaContainer}
 * instances as JVM-wide singletons, plus common helper methods for creating
 * events, waiting for index refreshes, and asserting ES document state.
 *
 * <p>Containers are started once and reused across all test subclasses to
 * avoid the overhead of per-class container lifecycle. The containers are
 * stopped when the JVM exits via a shutdown hook.
 *
 * <p>Uses {@code @DynamicPropertySource} to wire container addresses into
 * Spring Boot configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSearchConfig.class)
public abstract class AbstractIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Singleton Container Instances (JVM-wide)
    // ═══════════════════════════════════════════════════════════════════

    private static final AtomicBoolean containersStarted = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    protected static ElasticsearchContainer ES_CONTAINER;
    protected static KafkaContainer KAFKA_CONTAINER;

    static {
        startContainersOnce();
    }

    /**
     * Start both containers once per JVM. Subsequent calls are no-ops.
     * Uses synchronized to prevent race conditions in parallel test execution.
     */
    private static void startContainersOnce() {
        if (containersStarted.get()) return;
        synchronized (LOCK) {
            if (containersStarted.get()) return;

            System.out.println("[TestContainers] Starting containers...");

            ES_CONTAINER = new ElasticsearchContainer(
                    "docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
                .withStartupTimeout(Duration.ofMinutes(5))
                .withReuse(false);
            ES_CONTAINER.start();

            KAFKA_CONTAINER = new KafkaContainer(
                    DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
                .withStartupTimeout(Duration.ofMinutes(5))
                .withReuse(false);
            KAFKA_CONTAINER.start();

            // Register shutdown hook to stop containers on JVM exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[TestContainers] Stopping containers...");
                stopSilently(ES_CONTAINER);
                stopSilently(KAFKA_CONTAINER);
            }));

            containersStarted.set(true);
            System.out.println("[TestContainers] Containers started: ES="
                + ES_CONTAINER.getHttpHostAddress() + ", Kafka="
                + KAFKA_CONTAINER.getBootstrapServers());
        }
    }

    private static void stopSilently(Startable container) {
        try {
            container.stop();
        } catch (Exception e) {
            System.err.println("[TestContainers] Error stopping container: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Dynamic Properties
    // ═══════════════════════════════════════════════════════════════════

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () ->
            "http://" + ES_CONTAINER.getHttpHostAddress());
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("search.topics.product", () -> "t.product.events");
        registry.add("search.topics.category", () -> "t.category.events");
        registry.add("search.topics.review", () -> "t.review.events");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Injected Beans
    // ═══════════════════════════════════════════════════════════════════

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ElasticsearchClient esClient;

    @Autowired
    protected KafkaTemplate<String, CloudEventEnvelope> kafkaTemplate;

    @Autowired
    protected SearchProperties searchProperties;

    @Autowired
    protected ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════════
    //  Shared Mapper
    // ═══════════════════════════════════════════════════════════════════

    protected static final ObjectMapper SNAKE_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @BeforeAll
    static void setUpContainers() throws Exception {
        // Containers are already started in the static initializer.
        System.out.println("ES container: " + ES_CONTAINER.getHttpHostAddress());
        System.out.println("Kafka container: " + KAFKA_CONTAINER.getBootstrapServers());

        // Delete any documents left by previous test classes to prevent
        // data leakage. The indices and their mappings are preserved.
        HttpClient httpClient = HttpClient.newHttpClient();
        for (String index : List.of("products_v1", "categories_v1", "search_suggestions_v1")) {
            try {
                String deleteUrl = "http://" + ES_CONTAINER.getHttpHostAddress()
                    + "/" + index + "/_delete_by_query?refresh=true&pretty";
                String body = "{\"query\":{\"match_all\":{}}}";
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(body))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                System.err.println("[TestContainers] Failed to clean index " + index + ": " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helper Methods
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Wait for Elasticsearch to fully refresh and make documents available
     * for search.
     */
    protected void refreshESIndex(String... indices) throws Exception {
        for (String index : indices) {
            esClient.indices().refresh(r -> r.index(index));
        }
    }

    /**
     * Create a CloudEvent envelope for a {@code PRODUCT_CREATED} event.
     */
    protected CloudEventEnvelope createProductCreatedEvent(
            String productId, String categoryId, String name, String status,
            long originalPrice, long bestPrice, boolean isInStock) {

        JsonNode payload = SNAKE_MAPPER.valueToTree(Map.of(
            "product_id", productId,
            "category_id", categoryId,
            "name", name,
            "status", status,
            "created_at", Instant.now().toString(),
            "updated_at", Instant.now().toString(),
            "skus", java.util.List.of(Map.of(
                "sku_id", productId + "-001",
                "sku_status", "ACTIVE",
                "original_price", originalPrice,
                "promotional_price", bestPrice,
                "best_price", bestPrice,
                "has_promotion", bestPrice < originalPrice,
                "is_in_stock", isInStock
            ))
        ));

        return new CloudEventEnvelope(
            UUID.randomUUID().toString(),
            "PRODUCT_CREATED",
            "Product",
            productId,
            "corr-" + UUID.randomUUID().toString().substring(0, 8),
            productId,
            Instant.now().toString(),
            "test",
            1,
            payload
        );
    }

    /**
     * Create a CloudEvent envelope for a {@code CATEGORY_CREATED} event.
     */
    protected CloudEventEnvelope createCategoryCreatedEvent(
            String categoryId, String name, String path, int level, String parentId) {

        JsonNode payload = SNAKE_MAPPER.valueToTree(Map.of(
            "category_id", categoryId,
            "name", name,
            "slug", name.toLowerCase().replace(" ", "-"),
            "parent_id", parentId,
            "level", level,
            "is_leaf", level >= 2,
            "path", path
        ));

        return new CloudEventEnvelope(
            UUID.randomUUID().toString(),
            "CATEGORY_CREATED",
            "Category",
            categoryId,
            "corr-" + UUID.randomUUID().toString().substring(0, 8),
            categoryId,
            Instant.now().toString(),
            "test",
            1,
            payload
        );
    }

    /**
     * Await until a condition is met, with a generous timeout
     * for containerised environments.
     */
    protected void awaitUntil(Runnable assertion) {
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .pollDelay(1, TimeUnit.SECONDS)
            .ignoreExceptions()
            .untilAsserted(assertion::run);
    }
}
