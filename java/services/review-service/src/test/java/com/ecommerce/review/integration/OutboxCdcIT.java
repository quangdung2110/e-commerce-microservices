package com.ecommerce.review.integration;

import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.Patch;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.RatingAggregateService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.TakedownService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-003, end to end: the outbox row really is the wire message.
 *
 * <p>This runs the connector configuration that ships with the service, not a copy, so it
 * also proves the settings that are easy to tidy up and expensive to get wrong: the
 * static {@code route.topic.replacement} that keeps all eight types on one topic,
 * {@code table.expand.json.payload} that puts the envelope on the wire instead of an
 * escaped string, and {@code table.include.list} naming the partitioned parent.
 *
 * <p>It is also the only place the self-consuming rating aggregator exists as a whole
 * path: Review has no business Kafka producer, so without CDC no lifecycle fact ever
 * reaches the topic and the aggregate never moves.
 */
@TestPropertySource(properties = "review.consumers.auto-startup=true")
class OutboxCdcIT extends AbstractReviewIT {

    private static final String CONNECT_ALIAS = "review-connect";
    private static final String TOPIC = "t.review.events";
    private static final String CONNECTOR_NAME = "review-outbox-connector-it";

    private static final GenericContainer<?> CONNECT = new GenericContainer<>(
            DockerImageName.parse("debezium/connect:3.0.0.Final"))
            .withNetwork(NETWORK)
            .withNetworkAliases(CONNECT_ALIAS)
            .withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", KAFKA_ALIAS + ":9092")
            .withEnv("GROUP_ID", "review-cdc-it")
            .withEnv("CONFIG_STORAGE_TOPIC", "review_cdc_it_configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "review_cdc_it_offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "review_cdc_it_status")
            .waitingFor(Wait.forHttp("/connectors").forPort(8083).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ModerationService moderation;

    @Autowired
    private AdminReplyService adminReplies;

    @Autowired
    private TakedownService takedowns;

    @Autowired
    private RatingAggregateService ratingAggregates;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    private static KafkaListenerEndpointRegistry registryForShutdown;

    private final UUID buyer = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeAll
    static void startConnect() {
        CONNECT.start();
    }

    /**
     * Leaves nothing running behind this class.
     *
     * <p>Spring caches the context, so the listeners this class enables would otherwise
     * keep consuming while later test classes truncate the tables underneath them, and the
     * connector would keep publishing into the topic they read.
     */
    @AfterAll
    static void stopConsumingAndPublishing() throws Exception {
        if (registryForShutdown != null) {
            registryForShutdown.stop();
        }
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + CONNECT.getHost() + ":"
                        + CONNECT.getMappedPort(8083) + "/connectors/" + CONNECTOR_NAME))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyMessageTypeReachesTheOneTopicUnchanged() throws Exception {
        registryForShutdown = listeners;
        registerConnector();

        String productId = "p-cdc";
        produceAllEightTypes(productId);

        List<Map<String, String>> stored = storedOutboxRows(productId);
        assertThat(stored).extracting(row -> row.get("event_type"))
                .contains(ReviewEventType.REVIEW_CREATED.type(),
                        ReviewEventType.REVIEW_UPDATED.type(),
                        ReviewEventType.REVIEW_HIDDEN.type(),
                        ReviewEventType.REVIEW_APPROVED.type(),
                        ReviewEventType.REVIEW_REMOVED.type(),
                        ReviewEventType.REVIEW_METRICS_UPDATED.type(),
                        ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type(),
                        ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED.type());

        Set<String> required = new HashSet<>();
        stored.forEach(row -> required.add(row.get("event_id")));
        Map<String, ConsumerRecord<String, String>> delivered = consumeUntil(required);

        for (Map<String, String> row : stored) {
            ConsumerRecord<String, String> record = delivered.get(row.get("event_id"));
            assertThat(record)
                    .as("outbox row %s must reach the topic", row.get("event_type"))
                    .isNotNull();
            // One topic for every type: the connector pins a literal replacement, because
            // EventRouter would otherwise route by aggregate_type, split Review across
            // several topics and break ordering per product.
            assertThat(record.topic()).isEqualTo(TOPIC);
            // The Kafka key is always the product, which is what preserves that ordering.
            assertThat(record.key()).isEqualTo(row.get("partition_key"));
            // The envelope arrives as a JSON object rather than an escaped string: that is
            // what table.expand.json.payload buys, and it is what makes the stored row and
            // the wire message the same document.
            assertThat(readTree(record.value()))
                    .as("%s must reach the wire exactly as stored", row.get("event_type"))
                    .isEqualTo(readTree(row.get("payload")));
        }
    }

    /** The aggregate only ever moves because Debezium published the fact that moved it. */
    @Test
    void theRatingAggregatorConsumesWhatTheConnectorPublishes() throws Exception {
        registryForShutdown = listeners;
        registerConnector();

        String productId = "p-cdc-aggregator";
        UUID eligibilityId = grantEntitlement("order-cdc-agg", productId, "sku-1", buyer);
        reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null));

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(jdbc.sql("""
                        SELECT total_reviews FROM product_rating_aggregates WHERE product_id = ?
                        """).param(productId).query(Long.class).optional())
                        .contains(1L));
    }

    /**
     * {@code sequence} reaches the wire on the metrics snapshot and on nothing else, and
     * never as a zero standing in for its absence. A converter that dropped it would stay
     * invisible until Search stopped ordering correctly.
     */
    @Test
    void optionalEnvelopeAttributesSurviveTheRoundTrip() throws Exception {
        registryForShutdown = listeners;
        registerConnector();

        String productId = "p-cdc-optional";
        produceAllEightTypes(productId);

        Set<String> required = new HashSet<>();
        storedOutboxRows(productId).forEach(row -> required.add(row.get("event_id")));
        Map<String, ConsumerRecord<String, String>> delivered = consumeUntil(required);

        List<JsonNode> envelopes = delivered.values().stream()
                .map(record -> readTree(record.value()))
                .toList();

        JsonNode metrics = envelopes.stream()
                .filter(envelope -> ReviewEventType.REVIEW_METRICS_UPDATED.type()
                        .equals(envelope.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(metrics.path("sequence").asLong())
                .isEqualTo(metrics.path("data").path("review_revision").asLong());

        assertThat(envelopes.stream()
                .filter(envelope -> !ReviewEventType.REVIEW_METRICS_UPDATED.type()
                        .equals(envelope.path("type").asText()))
                .noneMatch(envelope -> envelope.has("sequence")))
                .as("only the metrics snapshot carries a sequence")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /** One review taken through its whole life, which is what emits all eight types. */
    private void produceAllEightTypes(String productId) {
        UUID eligibilityId = grantEntitlement("order-cdc-" + productId, productId, "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();

        reviewCommands.edit(buyer, reviewId,
                new PatchReviewRequest(Patch.of(5), Patch.absent(), Patch.absent()));

        for (int i = 0; i < properties.reportThreshold(); i++) {
            moderation.report(UUID.randomUUID(), reviewId,
                    new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
        }
        moderation.dismissReports(admin, reviewId);

        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));

        // The aggregator snapshot, produced the way its consumer produces one.
        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_CREATED,
                objectMapper.createObjectNode()
                        .put("review_id", reviewId.toString())
                        .put("product_id", productId)
                        .put("sku_id", "sku-1")
                        .put("rating", 5)
                        .put("has_media", false),
                properties.topics().review(), 0, nextOffset());

        takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-CDC", "vi phạm"));
    }

    private List<Map<String, String>> storedOutboxRows(String productId) {
        return jdbc.sql("""
                SELECT event_id::text AS event_id, event_type, partition_key, payload::text AS payload
                  FROM review_outbox WHERE partition_key = ? ORDER BY created_at, event_type
                """).param(productId).query((rs, n) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("event_id", rs.getString("event_id"));
            row.put("event_type", rs.getString("event_type"));
            row.put("partition_key", rs.getString("partition_key"));
            row.put("payload", rs.getString("payload"));
            return row;
        }).list();
    }

    /**
     * Registers the connector configuration that ships with the service, with only the
     * host, database and slot name pointed at this test's containers.
     */
    private void registerConnector() throws Exception {
        JsonNode shipped = objectMapper.readTree(Files.readString(
                Path.of("src/main/resources/debezium/review-postgres-connector.json")));
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) shipped.get("config");
        config.put("database.hostname", POSTGRES_ALIAS);
        config.put("database.port", "5432");
        config.put("database.dbname", POSTGRES.getDatabaseName());
        config.put("database.user", POSTGRES.getUsername());
        config.put("database.password", POSTGRES.getPassword());
        config.put("slot.name", "review_cdc_it_slot");
        var body = objectMapper.createObjectNode();
        body.put("name", CONNECTOR_NAME);
        body.set("config", config);

        String connectUrl = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083) + "/connectors";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(connectUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 409 means an earlier test in this class already registered it.
        assertThat(response.statusCode())
                .as("connector registration: %s", response.body())
                .isIn(201, 409);

        // Polled by hand rather than with a bare condition: a connector that fails to
        // start says exactly why in its status, and losing that to a timeout message would
        // make every failure here look identical.
        String lastStatus = "no status yet";
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            lastStatus = connectorStatus(connectUrl);
            if (isRunning(lastStatus)) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("the Review outbox connector never reached RUNNING: " + lastStatus);
    }

    private String connectorStatus(String connectUrl) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(connectUrl + "/" + CONNECTOR_NAME + "/status"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            return "status unavailable: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private boolean isRunning(String status) {
        try {
            JsonNode body = objectMapper.readTree(status);
            return "RUNNING".equals(body.path("connector").path("state").asText())
                    && body.path("tasks").size() > 0
                    && "RUNNING".equals(body.path("tasks").get(0).path("state").asText());
        } catch (Exception notJsonYet) {
            return false;
        }
    }

    /**
     * Reads from the beginning until every required event id has arrived.
     *
     * <p>It waits for a set rather than a count: the live rating aggregator writes its own
     * metrics snapshots onto this topic while the test runs, so the number of messages is
     * not fixed and asserting on it would be asserting on timing.
     */
    private Map<String, ConsumerRecord<String, String>> consumeUntil(Set<String> requiredEventIds) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "review-cdc-it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        Map<String, ConsumerRecord<String, String>> collected = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (System.nanoTime() < deadline && !collected.keySet().containsAll(requiredEventIds)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2))) {
                    collected.put(readTree(record.value()).path("id").asText(), record);
                }
            }
            if (!collected.keySet().containsAll(requiredEventIds)) {
                Set<String> missing = new HashSet<>(requiredEventIds);
                missing.removeAll(collected.keySet());
                // Nothing arriving means the connector is not publishing where the test
                // looks, and only the broker topic list and the connector log can say
                // which of the two is wrong.
                throw new AssertionError(("%d outbox rows never reached %s: %s%ntopics: %s%nconnect log:%n%s")
                        .formatted(missing.size(), TOPIC, missing,
                                consumer.listTopics().keySet(), tailOfConnectLog()));
            }
        }
        return collected;
    }

    private static String tailOfConnectLog() {
        String log = CONNECT.getLogs();
        return log.length() <= 6000 ? log : log.substring(log.length() - 6000);
    }
}
