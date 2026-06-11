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
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the Inventory Sync Two-Phase worker.
 * Phase 1: Lock-Free Fast Sync consumer for t.inventory.events topic.
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>Separate consumer factory from event handler (dedicated group)</li>
 *   <li>Manual offset commit for precise control (ack after Lua CAS succeeds)</li>
 *   <li>High concurrency (3 threads) for throughput</li>
 *   <li>Fixed backoff retry (200ms, 5 attempts) before DLQ</li>
 *   <li>Latest offset reset (want current state, not replay)</li>
 * </ul>
 */
@Configuration
@EnableKafka
public class InventoryKafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(InventoryKafkaConsumerConfig.class);

    private static final int RETRY_COUNT = 5;
    private static final long RETRY_INTERVAL_MS = 200L;
    private static final int CONTAINER_CONCURRENCY = 3;
    private static final int MAX_POLL_RECORDS = 500;
    private static final int FETCH_MIN_BYTES = 1;
    private static final int FETCH_MAX_WAIT_MS = 100;
    private static final String DLQ_SUFFIX = ".DLQ";

    private final String bootstrapServers;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public InventoryKafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.bootstrapServers = bootstrapServers;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Bean
    public ConsumerFactory<String, String> inventorySyncConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, ServiceConstants.CG_INVENTORY_SYNC);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, FETCH_MIN_BYTES);
        configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS);
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        log.info("InventoryKafkaConsumerConfig: creating consumer factory for group={}, autoOffsetReset=latest, " +
                        "maxPollRecords={}, concurrency={}",
                ServiceConstants.CG_INVENTORY_SYNC, MAX_POLL_RECORDS, CONTAINER_CONCURRENCY);

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> inventorySyncListenerContainerFactory(
            ConsumerFactory<String, String> inventorySyncConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventorySyncConsumerFactory);
        factory.setConcurrency(CONTAINER_CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        DefaultErrorHandler errorHandler = buildErrorHandler();
        factory.setCommonErrorHandler(errorHandler);

        log.info("InventoryKafkaConsumerConfig: configured inventory sync listener factory with " +
                        "concurrency={}, ackMode=MANUAL_IMMEDIATE, retries={}, retryInterval={}ms",
                CONTAINER_CONCURRENCY, RETRY_COUNT, RETRY_INTERVAL_MS);

        return factory;
    }

    private DefaultErrorHandler buildErrorHandler() {
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, RETRY_COUNT);

        return new DefaultErrorHandler((record, exception) -> {
            log.error("InventoryKafkaConsumerConfig: all retries exhausted for inventory event " +
                            "[topic={}, partition={}, offset={}]: {}",
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
                        log.error("InventoryKafkaConsumerConfig: failed to send message to DLQ topic {}: {}",
                                dlqTopic, ex.getMessage());
                    } else {
                        log.warn("InventoryKafkaConsumerConfig: message sent to DLQ [dlqTopic={}, partition={}, " +
                                        "offset={}, reason={}]",
                                dlqTopic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                reason);
                    }
                });
    }
}
