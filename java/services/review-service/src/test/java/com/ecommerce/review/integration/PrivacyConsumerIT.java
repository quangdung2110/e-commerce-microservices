package com.ecommerce.review.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The {@code USER_DELETED} listener end to end, against a real broker (WF-REVIEW-010,
 * ADR-013).
 *
 * <p>A deletion that Review fails to apply leaves a legal deadline with nothing tracking
 * it: no guard, no erasure request, nothing for the overdue alert to count. So a transient
 * fault must be retried for as long as it lasts, and only a record that can never be
 * applied — one that violates its contract — may be dead-lettered.
 *
 * <p>The second property is a distinct Spring context on purpose. {@code OutboxCdcIT}
 * needs every listener running and stops them when it finishes; sharing its context would
 * let either class switch the other's consumers off.
 */
@TestPropertySource(properties = {"review.consumers.auto-startup=true", "review.it.privacy-consumer=true"})
class PrivacyConsumerIT extends AbstractReviewIT {

    private static final String AUTH_TOPIC = "t.auth.events";
    private static final String DEAD_LETTER_TOPIC = "t.auth.events.dlq";

    private static KafkaListenerEndpointRegistry registryForShutdown;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private MeterRegistry meters;

    /** Only the listener under test runs: the others would be background noise. */
    @BeforeEach
    void runOnlyThePrivacyListener() {
        registryForShutdown = listeners;
        for (MessageListenerContainer container : listeners.getListenerContainers()) {
            boolean privacy = properties.consumerGroups().privacy().equals(container.getGroupId());
            if (privacy && !container.isRunning()) {
                container.start();
            } else if (!privacy && container.isRunning()) {
                container.stop();
            }
        }
    }

    @AfterAll
    static void stopConsuming() {
        if (registryForShutdown != null) {
            registryForShutdown.stop();
        }
    }

    private static String userDeleted(UUID eventId, UUID userId) {
        return """
                {"specversion":"1.0","id":"%s","type":"com.ecommerce.auth.user-deleted.v1",
                 "source":"/services/auth","subject":"users/%s","time":"%s",
                 "datacontenttype":"application/json",
                 "dataschema":"/contracts/events/auth/v1/schemas/user-deleted.schema.json",
                 "data":{"user_id":"%s","deleted_at":"%s","reason":"GDPR_REQUEST","hard_delete_after":"%s"}}
                """.formatted(eventId, userId, Instant.now(), userId, Instant.now(),
                Instant.now().plus(Duration.ofDays(30)));
    }

    private void publish(String key, String value) throws ExecutionException, InterruptedException {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(AUTH_TOPIC, key, value)).get();
        }
    }

    private List<String> deadLetters() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-probe-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            if (consumer.listTopics().get(DEAD_LETTER_TOPIC) == null) {
                return List.of();
            }
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            List<String> values = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 4_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
            return values;
        }
    }

    private long privacyRequests(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM privacy_erasure_requests WHERE source_event_id IS NOT NULL "
                + "AND subject_user_id = ?").param(userId).query(Long.class).single();
    }

    /** A record no retry can repair goes straight to the dead-letter topic and blocks nothing. */
    @Test
    void aRecordThatViolatesItsContractIsDeadLetteredAtOnceAndTheNextOneIsApplied() throws Exception {
        String marker = "not-a-cloud-event-" + UUID.randomUUID();
        UUID user = UUID.randomUUID();

        publish(user.toString(), marker);
        publish(user.toString(), userDeleted(UUID.randomUUID(), user));

        await().atMost(Duration.ofSeconds(45)).untilAsserted(
                () -> assertThat(privacyRequests(user)).isEqualTo(1));
        assertThat(deadLetters()).anyMatch(value -> value.contains(marker));
    }

    /**
     * A transient fault is retried for as long as it lasts: the failures are counted, the
     * record is never dead-lettered, and it is applied as soon as the fault clears.
     */
    @Test
    void aTransientFailureIsRetriedWithoutDeadLetteringUntilItClears() throws Exception {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        double failuresBefore = meters.counter("review_privacy_consumer_failure_total").count();

        jdbc.sql("""
                CREATE OR REPLACE FUNCTION review.fail_privacy_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated transient failure';
                END $$
                """).update();
        jdbc.sql("CREATE TRIGGER trg_fail_privacy_insert BEFORE INSERT ON privacy_erasure_requests "
                + "FOR EACH ROW EXECUTE FUNCTION review.fail_privacy_insert()").update();
        try {
            publish(user.toString(), userDeleted(eventId, user));

            await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    assertThat(meters.counter("review_privacy_consumer_failure_total").count())
                            .isGreaterThanOrEqualTo(failuresBefore + 2));
            assertThat(privacyRequests(user)).isZero();
            assertThat(deadLetters()).noneMatch(value -> value.contains(eventId.toString()));
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS trg_fail_privacy_insert ON privacy_erasure_requests").update();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(
                () -> assertThat(privacyRequests(user)).isEqualTo(1));
        assertThat(deadLetters()).noneMatch(value -> value.contains(eventId.toString()));
    }
}
