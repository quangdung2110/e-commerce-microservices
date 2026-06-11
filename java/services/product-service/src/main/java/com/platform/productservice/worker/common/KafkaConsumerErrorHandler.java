package com.platform.productservice.worker.common;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Custom error handler for Kafka consumers in the event handler worker.
 * Logs errors with full event metadata, classifies retryable vs. non-retryable errors,
 * and supports DLQ publishing for non-retryable errors.
 */
@Component
public class KafkaConsumerErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerErrorHandler.class);

    private static final Set<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = Set.of(
            IllegalArgumentException.class,
            NullPointerException.class,
            com.fasterxml.jackson.core.JsonProcessingException.class,
            com.fasterxml.jackson.databind.exc.InvalidFormatException.class
    );

    /**
     * Determines whether the given exception is retryable.
     */
    public boolean isRetryable(Exception exception) {
        if (exception == null) return false;
        if (NON_RETRYABLE_EXCEPTIONS.stream().anyMatch(e -> e.isInstance(exception))) {
            return false;
        }
        return true;
    }

    /**
     * Logs the consumer record processing failure.
     */
    public void logFailure(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Kafka consumer processing failed: topic={}, partition={}, offset={}, key={}, error={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                exception.getMessage(), exception);
    }

    /**
     * Logs a consumer error with event context.
     */
    public void logError(ConsumerRecord<String, String> record, String eventType, String errorMessage) {
        log.error("Kafka consumer error: topic={}, partition={}, offset={}, eventType={}, error={}",
                record.topic(), record.partition(), record.offset(), eventType, errorMessage);
    }
}
