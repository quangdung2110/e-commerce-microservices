package com.ecommerce.search.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
 * ({@code docs/search/architecture.md §3}). There is no dead-letter topic and no
 * error-handler retry, because a transient failure is retried by simply not committing.
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
    public ConsumerFactory<String, String> searchConsumerFactory() {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(BATCH_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> searchBatchListenerFactory(
            ConsumerFactory<String, String> searchConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(searchConsumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // A failed batch is redelivered by Kafka itself; the container must not swallow it
        // into a retry loop or a dead-letter topic.
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                new FixedBackOff(1_000L, 3L)));
        return factory;
    }
}
