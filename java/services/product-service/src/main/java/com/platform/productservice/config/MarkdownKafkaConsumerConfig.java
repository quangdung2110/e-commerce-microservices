package com.platform.productservice.config;

import com.platform.productservice.common.constants.ServiceConstants;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the Markdown Price Calculator Worker.
 * Consumes events from t.promotion.markdown.events topic with manual offset commit,
 * error handling, and DLQ support.
 */
@Configuration
@EnableKafka
public class MarkdownKafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(MarkdownKafkaConsumerConfig.class);

    private static final int RETRY_COUNT = 3;
    private static final long RETRY_INTERVAL_MS = 1000;
    private static final long RETRY_MAX_INTERVAL_MS = 4000;
    private static final double RETRY_MULTIPLIER = 2.0;

    private static final int CONTAINER_CONCURRENCY = 2;
    private static final int MAX_POLL_RECORDS = 50;
    private static final String DLQ_SUFFIX = ".DLQ";

    private final String bootstrapServers;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public MarkdownKafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.bootstrapServers = bootstrapServers;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Bean
    public ConsumerFactory<String, String> markdownConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, ServiceConstants.CG_MARKDOWN);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> markdownListenerContainerFactory(
            ConsumerFactory<String, String> markdownConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(markdownConsumerFactory);
        factory.setConcurrency(CONTAINER_CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        DefaultErrorHandler errorHandler = buildDefaultErrorHandler();
        factory.setCommonErrorHandler(errorHandler);

        log.info("MarkdownKafkaConsumerConfig: configured markdown factory with concurrency={}, " +
                        "maxPollRecords={}, ackMode=MANUAL_IMMEDIATE, retries={}",
                CONTAINER_CONCURRENCY, MAX_POLL_RECORDS, RETRY_COUNT);

        return factory;
    }

    private DefaultErrorHandler buildDefaultErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(RETRY_INTERVAL_MS, RETRY_MULTIPLIER);
        backOff.setMaxInterval(RETRY_MAX_INTERVAL_MS);
        backOff.setMaxElapsedTime(RETRY_MAX_INTERVAL_MS * RETRY_COUNT);

        return new DefaultErrorHandler((record, exception) -> {
            log.error("Markdown consumer error after retries exhausted: topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage(), exception);
            sendToDlq(record, "retries_exhausted:" + exception.getMessage());
        }, backOff);
    }

    private void sendToDlq(ConsumerRecord<?, ?> record, String reason) {
        String dlqTopic = record.topic() + DLQ_SUFFIX;
        @SuppressWarnings("unchecked")
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
                dlqTopic,
                record.partition(),
                (String) record.key(),
                (String) record.value(),
                record.headers()
        );
        kafkaTemplate.send(dlqRecord)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send message to DLQ topic {}: {}", dlqTopic, ex.getMessage());
                    } else {
                        log.warn("Message sent to DLQ [dlqTopic={}, partition={}, offset={}, reason={}]",
                                dlqTopic, result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(), reason);
                    }
                });
    }
}
