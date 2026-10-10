package com.platform.promotionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-PROMO-007 end to end: the outbox row IS the wire message.
 *
 * <p>Chạy đúng file connector đóng gói trong service
 * ({@code src/main/resources/kafka/debezium-connector-promotion-outbox.json}), chỉ trỏ host/database/slot về
 * container của test, nên cũng chứng minh các thiết lập dễ bị "dọn dẹp" nhầm: {@code table.expand.json.payload}
 * (envelope lên wire là object chứ không phải chuỗi escape), key lấy từ {@code partition_key}, route theo
 * {@code event_type} rồi {@code RegexRouter} về {@code t.promotion.markdown.events}, và header {@code ce_*}.
 * Phía Product consumer được test riêng ({@code MarkdownConsumerIntegrationTest} của product-service).
 *
 * <p>Bỏ qua khi dùng dependency có sẵn ({@code TEST_DB_URL}) vì cần PostgreSQL {@code wal_level=logical} trong
 * cùng mạng Docker với Debezium.
 */
class OutboxCdcIntegrationTest extends AbstractPromotionIntegrationTest {

    private static final String KAFKA_ALIAS = "promotion-kafka";
    private static final String CONNECT_ALIAS = "promotion-connect";
    private static final String TOPIC = "t.promotion.markdown.events";
    private static final String CONNECTOR_NAME = "promotion-outbox-connector-it";
    private static final String SCHEMA_ID_PREFIX = "https://contracts.ecommerce.local/";

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(NETWORK)
            .withNetworkAliases(KAFKA_ALIAS);

    private static final GenericContainer<?> CONNECT = new GenericContainer<>(
            DockerImageName.parse("debezium/connect:3.0.0.Final"))
            .withNetwork(NETWORK)
            .withNetworkAliases(CONNECT_ALIAS)
            .withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", KAFKA_ALIAS + ":9092")
            .withEnv("GROUP_ID", "promotion-cdc-it")
            .withEnv("CONFIG_STORAGE_TOPIC", "promotion_cdc_it_configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "promotion_cdc_it_offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "promotion_cdc_it_status")
            .waitingFor(Wait.forHttp("/connectors").forPort(8083).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static boolean started;

    @BeforeAll
    static void startKafkaAndConnect() {
        Assumptions.assumeTrue(POSTGRES != null, "cần PostgreSQL wal_level=logical của Testcontainers (không dùng TEST_DB_URL)");
        KAFKA.start();
        CONNECT.start();
        started = true;
    }

    @AfterAll
    static void stopKafkaAndConnect() throws Exception {
        if (!started) {
            return;
        }
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(connectUrl() + "/" + CONNECTOR_NAME)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        CONNECT.stop();
        KAFKA.stop();
    }

    @Test
    @DisplayName("publish then pause: every outbox row reaches t.promotion.markdown.events unchanged, keyed by product, in revision order")
    void outboxRowsReachTheMarkdownTopicUnchanged() throws Exception {
        registerConnector();

        UUID product = UUID.randomUUID();
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        // Percentage without a cap: max_discount_vnd is an explicit null on the wire and must survive the round trip.
        givenMarkdownRule(campaign, product, 5);
        givenPromotion(campaign, com.platform.promotionservice.domain.enums.DistributionMode.PUBLIC_SAVE, null);
        CampaignEntity published = publish(campaign);
        transition(published, PublicationStatus.PAUSED);

        List<Map<String, String>> stored = storedRows(product);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(row -> readTree(row.get("payload")).path("data").path("state").asText())
                .containsExactly("ACTIVE", "INACTIVE");

        Set<String> required = new HashSet<>();
        stored.forEach(row -> required.add(row.get("event_id")));
        Map<String, ConsumerRecord<String, String>> delivered = consumeUntil(required);

        JsonSchema schema = markdownSchema();
        long previousRevision = 0;
        long previousOffset = -1;
        for (Map<String, String> row : stored) {
            ConsumerRecord<String, String> record = delivered.get(row.get("event_id"));
            assertThat(record).as("outbox row %s must reach the topic", row.get("event_id")).isNotNull();
            assertThat(record.topic()).isEqualTo(TOPIC);
            // Kafka key = product_id: the ordering scope of the markdown snapshot.
            assertThat(record.key()).isEqualTo(product.toString()).isEqualTo(row.get("partition_key"));
            // The envelope is a JSON object equal to the document stored in the outbox, except that
            // EventRouter (table.expand.json.payload) omits fields whose value is an explicit null.
            JsonNode wire = readTree(record.value());
            assertThat(wire).as("wire message must equal the stored payload without null fields")
                    .isEqualTo(withoutNulls(readTree(row.get("payload"))));
            // max_discount_vnd is null in the stored payload and absent on the wire: consumers treat both alike.
            if ("ACTIVE".equals(wire.path("data").path("state").asText())) {
                assertThat(readTree(row.get("payload")).path("data").path("discount").has("max_discount_vnd")).isTrue();
                assertThat(wire.path("data").path("discount").has("max_discount_vnd")).isFalse();
            }
            assertThat(wire.path("id").asText()).isEqualTo(row.get("event_id"));
            // The wire message also honours the published JSON Schema.
            assertThat(schema.validate(wire)).as("wire message violates the JSON Schema").isEmpty();
            // Headers added by the connector.
            assertThat(header(record, "ce_type")).isEqualTo("com.ecommerce.promotion.product-markdown-snapshot.v1");
            assertThat(header(record, "ce_aggregate_id")).isEqualTo(product.toString());
            assertThat(header(record, "ce_source_revision")).isEqualTo(row.get("source_revision"));
            // Same key, same partition: delivery order follows the source revision, which Product uses to drop stale ones.
            long revision = wire.path("data").path("source_revision").asLong();
            assertThat(revision).isGreaterThan(previousRevision);
            assertThat(wire.path("sequence").asLong()).isEqualTo(revision);
            assertThat(record.offset()).isGreaterThan(previousOffset);
            previousRevision = revision;
            previousOffset = record.offset();
        }
    }

    // ------------------------------------------------------------------ fixture

    private List<Map<String, String>> storedRows(UUID product) {
        return jdbcTemplate.query("""
                SELECT event_id::text, partition_key, source_revision::text, payload::text
                FROM promotion_outbox_events WHERE partition_key = ? ORDER BY source_revision
                """, (rs, i) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("event_id", rs.getString(1));
            row.put("partition_key", rs.getString(2));
            row.put("source_revision", rs.getString(3));
            row.put("payload", rs.getString(4));
            return row;
        }, product.toString());
    }

    private static String connectUrl() {
        return "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083) + "/connectors";
    }

    /** Registers the shipped connector file; only host, database, credentials and slot are pointed at this test. */
    private void registerConnector() throws Exception {
        JsonNode shipped = objectMapper.readTree(Files.readString(
                Path.of("src/main/resources/kafka/debezium-connector-promotion-outbox.json")));
        ObjectNode config = (ObjectNode) shipped.get("config");
        config.put("database.hostname", POSTGRES_ALIAS);
        config.put("database.port", "5432");
        config.put("database.dbname", POSTGRES.getDatabaseName());
        config.put("database.user", POSTGRES.getUsername());
        config.put("database.password", POSTGRES.getPassword());
        config.put("slot.name", "promotion_cdc_it_slot");
        config.put("publication.name", "dbz_promotion_cdc_it_publication");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", CONNECTOR_NAME);
        body.set("config", config);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(connectUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("connector registration: %s", response.body()).isIn(201, 409);

        // Polled by hand: a connector that fails to start says exactly why in its status.
        String lastStatus = "no status yet";
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            lastStatus = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(connectUrl() + "/" + CONNECTOR_NAME + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            if (isRunning(lastStatus)) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("the Promotion outbox connector never reached RUNNING: " + lastStatus);
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

    /** Reads from the beginning until every required event id has arrived (a set, not a count). */
    private Map<String, ConsumerRecord<String, String>> consumeUntil(Set<String> requiredEventIds) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "promotion-cdc-it-" + UUID.randomUUID());
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
                List<String> missing = new ArrayList<>(requiredEventIds);
                missing.removeAll(collected.keySet());
                String log = CONNECT.getLogs();
                throw new AssertionError("%d outbox rows never reached %s: %s%ntopics: %s%nconnect log:%n%s".formatted(
                        missing.size(), TOPIC, missing, consumer.listTopics().keySet(),
                        log.length() <= 6000 ? log : log.substring(log.length() - 6000)));
            }
        }
        return collected;
    }

    private JsonSchema markdownSchema() {
        Path contracts = Paths.get("").toAbsolutePath().resolve("../../../contracts").normalize();
        Assumptions.assumeTrue(Files.isDirectory(contracts), "contracts/ chỉ có mặt khi test chạy trong repository");
        String contractsUri = contracts.toUri().toString();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, contractsUri)));
        return factory.getSchema(SchemaLocation.of(
                SCHEMA_ID_PREFIX + "events/promotion/v1/schemas/product-markdown-snapshot.schema.json"));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Copy of a JSON tree without object fields whose value is null (what the connector puts on the wire). */
    private static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = node.deepCopy();
            copy.removeAll();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    copy.set(entry.getKey(), withoutNulls(entry.getValue()));
                }
            });
            return copy;
        }
        return node;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
