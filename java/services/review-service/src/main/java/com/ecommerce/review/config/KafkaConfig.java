package com.ecommerce.review.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer wiring, and the one Kafka producer Review is allowed to have.
 *
 * <p>That producer publishes dead letters only. Every business event leaves through the
 * outbox and Debezium; a business message produced from application code would bypass the
 * transaction that made it true (ADR-003), and the architecture test pins that.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, String> deadLetterKafkaTemplate(KafkaProperties properties) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null)));
    }

    /**
     * Retries a failing record with backoff and then routes it to
     * {@code <topic>.dlq}, matching the {@code consumer-managed-retry-with-dlq} policy
     * every contract declares.
     *
     * <p>A privacy failure is the exception the runbook calls out: it alerts at critical
     * severity and must be resolved, not quietly buried in a dead-letter topic.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlq", record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            KafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(properties.getListener().getConcurrency() == null
                ? 1 : properties.getListener().getConcurrency());
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
