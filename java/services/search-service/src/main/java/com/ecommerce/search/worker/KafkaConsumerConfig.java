package com.ecommerce.search.worker;

import com.ecommerce.search.domain.event.CloudEventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Configures the main Kafka consumer infrastructure for the Search Service.
 *
 * <p>This is the single consumer group ({@value #CONSUMER_GROUP_ID}) that reads
 * from all three upstream topics ({@code t.product.events}, {@code t.category.events},
 * {@code t.review.events}) with a fixed concurrency of {@value #CONCURRENCY}
 * threads — one per expected partition.
 *
 * <h3>Error handling (Phase 22b — No DLQ)</h3>
 * <ul>
 *   <li><b>Transient</b> (ES timeout, 429, 503, network): throw → Spring Kafka
 *       retries with exponential backoff (1s → 5 min max).</li>
 *   <li><b>Payload poison</b> (invalid JSON): {@link ErrorHandlingDeserializer}
 *       catches → log + skip + ACK.</li>
 *   <li><b>Non-recoverable</b> (mapping conflict, document_missing_exception):
 *       caught by the custom recovery callback → log + metric + skip + ACK.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableKafka
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    public static final String CONSUMER_GROUP_ID = "search-product-indexer";

    /**
     * One thread per partition. Current partition assignment:
     * {@code t.product.events} (3 partitions) + {@code t.category.events} (1) +
     * {@code t.review.events} (1) = 5.
     */
    public static final int CONCURRENCY = 5;

    private final KafkaProperties kafkaProperties;

    /**
     * Main consumer factory for the {@code search-product-indexer} group.
     *
     * <p>Uses {@link ErrorHandlingDeserializer} wrapping {@link JsonDeserializer}
     * so that malformed payloads (invalid JSON, missing fields) are caught at the
     * deserialization boundary rather than crashing the consumer loop.
     */
    @Bean
    public ConsumerFactory<String, CloudEventEnvelope> consumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        // Core consumer identity
        props.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP_ID);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class);

        // Resilient value deserialisation: ErrorHandlingDeserializer wraps JsonDeserializer
        // so a single poison pill does not crash the entire consumer.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ecommerce.search.domain.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
            "com.ecommerce.search.domain.event.CloudEventEnvelope");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Listener container factory configured for <b>batch</b> consumption with
     * <b>manual immediate</b> acknowledgment.
     *
     * <p>Error handling follows the Phase 22b policy:
     * <ol>
     *   <li>Exponential backoff for transient errors (1 ms → 2 ms → 4 ms → … → 5 min max).</li>
     *   <li>After all retries are exhausted the recovery callback logs the error,
     *       increments a metric, and <em>commits the offset</em> so the consumer
     *       does not get stuck.</li>
     * </ol>
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope>
    kafkaListenerContainerFactory(
            ConsumerFactory<String, CloudEventEnvelope> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope> factory =
            new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setBatchListener(true);

        // Phase 22b — No DLQ. Exponential backoff for transient errors.
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(300_000L);  // 1s, 2s, 4s, … capped at 5 min

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            (record, exception) -> {
                // Non-recoverable → log + skip + ACK (no DLQ)
                log.error(
                    "Non-recoverable error processing record, skipping. "
                        + "topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    exception);
                // TODO: increment counter metric
                //   Metrics.counter("search.event.error.total",
                //     "topic", record.topic(),
                //     "eventType", "unknown")
                //   .increment();
            },
            backOff
        );
        errorHandler.setCommitRecovered(true);
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
