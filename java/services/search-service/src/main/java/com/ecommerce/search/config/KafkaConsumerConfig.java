package com.ecommerce.search.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka consumers for the five inbound topics.
 *
 * <p>Records are consumed as raw JSON strings and bound only after the CloudEvents
 * {@code type} has been checked against the allowlist — Search shares these topics with
 * other consumers, so a typed deserialiser would turn another team's event into a
 * deserialisation error.
 *
 * <p>Acknowledgement is manual and batched: an offset is committed only once every record
 * in the batch has been applied or classified as non-recoverable
 * ({@code docs/search/architecture.md §3}). There is no dead-letter topic. A batch that must
 * be retried is handed back with {@code Acknowledgment.nack} by {@code IndexingConsumer};
 * merely leaving it unacknowledged would not redeliver it.
 *
 * <p>The standard Kafka client metrics are bound to the registry, which is where consumer lag
 * comes from: {@code kafka_consumer_fetch_manager_records_lag_max} per consumer, and
 * {@code kafka_consumer_fetch_manager_records_lag} per topic and partition once the partition has
 * been fetched (Prometheus names; Micrometer names them {@code kafka.consumer.fetch.manager.*}).
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    public static final String BATCH_FACTORY = "searchBatchListenerFactory";

    private final KafkaProperties kafkaProperties;

    public KafkaConsumerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ConsumerFactory<String, String> searchConsumerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean(BATCH_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> searchBatchListenerFactory(
            ConsumerFactory<String, String> searchConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(searchConsumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // This handler only sees an exception that escapes the listener, which is a bug in the
        // handlers rather than an Elasticsearch or readiness failure (those become nack). After
        // three attempts one second apart the batch is logged and skipped, the same
        // non-recoverable treatment as an unparsable record (ADR-005).
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                new FixedBackOff(1_000L, 3L)));
        return factory;
    }
}
