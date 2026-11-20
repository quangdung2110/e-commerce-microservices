package com.ecommerce.review.config;

import com.ecommerce.review.messaging.InboundEvents;
import com.ecommerce.review.metrics.ReviewMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
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
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties());
    }

    @Bean
    public KafkaTemplate<String, String> deadLetterKafkaTemplate(KafkaProperties properties) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties.buildProducerProperties()));
    }

    /**
     * Retries a failing record with backoff and then routes it to
     * {@code <topic>.dlq}, matching the {@code consumer-managed-retry-with-dlq} policy
     * every contract declares.
     *
     * <p>A record that violates its contract cannot be fixed by trying again, so it goes to
     * the dead-letter topic at once instead of after a minute of identical failures
     * (WF-REVIEW-001).
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);
        return contractViolationsGoStraightToDeadLetter(
                new DefaultErrorHandler(deadLetterRecoverer(deadLetterKafkaTemplate), backOff));
    }

    /**
     * The error handler of the {@code USER_DELETED} listener, which must never lose an
     * event to a transient fault.
     *
     * <p>A lost deletion leaves a legal deadline with nothing tracking it: no guard, no
     * erasure request, nothing for the overdue alert to count. So a failure that is not a
     * contract violation is retried without a time limit, with the delay capped at one
     * minute, and every failed delivery is counted so the stall is alertable. Only a record
     * that violates its contract — which no retry can repair — is dead-lettered
     * (WF-REVIEW-010, ADR-013).
     *
     * @param recoverer receives a record that is skipped; the production one publishes it to
     *                  {@code <topic>.dlq}
     */
    public static DefaultErrorHandler privacyErrorHandler(ConsumerRecordRecoverer recoverer, ReviewMetrics metrics) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(60_000L);
        DefaultErrorHandler handler = contractViolationsGoStraightToDeadLetter(
                new DefaultErrorHandler(recoverer, backOff));
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
                metrics.privacyConsumerFailure();
            }
        });
        return handler;
    }

    @Bean
    public DefaultErrorHandler privacyKafkaErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate,
                                                        ReviewMetrics metrics) {
        return privacyErrorHandler(deadLetterRecoverer(deadLetterKafkaTemplate), metrics);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Qualifier("kafkaErrorHandler") DefaultErrorHandler errorHandler,
            KafkaProperties properties) {
        return containerFactory(consumerFactory, errorHandler, properties);
    }

    /** The factory of the {@code USER_DELETED} listener alone; see {@link #privacyErrorHandler}. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> privacyKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Qualifier("privacyKafkaErrorHandler") DefaultErrorHandler errorHandler,
            KafkaProperties properties) {
        return containerFactory(consumerFactory, errorHandler, properties);
    }

    private static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> template) {
        return new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlq", record.partition()));
    }

    private static DefaultErrorHandler contractViolationsGoStraightToDeadLetter(DefaultErrorHandler handler) {
        handler.addNotRetryableExceptions(InboundEvents.ContractViolationException.class);
        return handler;
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> containerFactory(
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
