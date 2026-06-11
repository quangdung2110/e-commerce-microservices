package com.platform.productservice.config;

import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.infrastructure.kafka.interceptor.MicrometerConsumerInterceptor;
import com.platform.productservice.infrastructure.kafka.interceptor.MicrometerProducerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final int RETRY_COUNT = 3;
    private static final long RETRY_INTERVAL_MS = 1000;

    private static final int CONTAINER_CONCURRENCY = 3;
    private static final String DLQ_SUFFIX = ".DLQ";

    private static final int TOPIC_PRODUCT_EVENTS_PARTITIONS = 12;
    private static final int TOPIC_CATEGORY_EVENTS_PARTITIONS = 6;
    private static final int TOPIC_DLQ_PARTITIONS = 3;

    private final String bootstrapServers;
    private final String consumerGroupId;
    private final MeterRegistry meterRegistry;

    public KafkaConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:product-service-group}") String consumerGroupId,
            MeterRegistry meterRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.consumerGroupId = consumerGroupId;
        this.meterRegistry = meterRegistry;
    }

    /* =========================================================
       Producer Configuration
       ========================================================= */

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        configProps.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                List.of(MicrometerProducerInterceptor.class.getName()));
        configProps.put("spring.micrometer.metrics.meter-registry", meterRegistry);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setProducerListener(new ProducerListener<>() {
            @Override
            public void onSuccess(String topic, Integer partition, Object key, Object value, RecordMetadata recordMetadata) {
                log.debug("Kafka produce success: topic={}, partition={}, offset={}",
                        topic, partition, recordMetadata.offset());
            }

            @Override
            public void onError(String topic, Integer partition, Object key, Object value,
                    Exception exception, ProducerRecord<String, String> record) {
                log.error("Kafka produce error: topic={}, partition={}, error={}",
                        topic, partition, exception.getMessage(), exception);
            }
        });
        return template;
    }

    /* =========================================================
       Consumer Configuration
       ========================================================= */

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        configProps.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, true);
        configProps.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
                List.of(MicrometerConsumerInterceptor.class.getName()));
        configProps.put("spring.micrometer.metrics.meter-registry", meterRegistry);
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(CONTAINER_CONCURRENCY);
        factory.getContainerProperties().setAckMode(AckMode.RECORD);

        DefaultErrorHandler errorHandler = buildDefaultErrorHandler(kafkaTemplate);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    private DefaultErrorHandler buildDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        FixedBackOff fixedBackOff = new FixedBackOff(RETRY_INTERVAL_MS, RETRY_COUNT);

        return new DefaultErrorHandler((record, exception) -> {
            log.error("Kafka consume error after retries exhausted: topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage(), exception);
            sendToDlq(kafkaTemplate, record);
        }, fixedBackOff);
    }

    private void sendToDlq(KafkaTemplate<String, String> kafkaTemplate,
                            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        String dlqTopic = record.topic() + DLQ_SUFFIX;
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
                dlqTopic,
                record.partition(),
                record.key(),
                record.value(),
                record.headers()
        );
        kafkaTemplate.send(dlqRecord)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send message to DLQ topic {}: {}", dlqTopic, ex.getMessage());
                    } else {
                        log.warn("Message sent to DLQ topic {} partition {} offset {}",
                                dlqTopic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }

    /* =========================================================
       Topic Definitions
       ========================================================= */

    @Bean
    public org.apache.kafka.clients.admin.NewTopic topicProductEvents() {
        return TopicBuilder.name(ServiceConstants.TOPIC_PRODUCT_EVENTS)
                .partitions(TOPIC_PRODUCT_EVENTS_PARTITIONS)
                .replicas(1)
                .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic topicCategoryEvents() {
        return TopicBuilder.name(ServiceConstants.TOPIC_CATEGORY_EVENTS)
                .partitions(TOPIC_CATEGORY_EVENTS_PARTITIONS)
                .replicas(1)
                .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic topicProductEventsDlq() {
        return TopicBuilder.name(ServiceConstants.TOPIC_PRODUCT_EVENTS + DLQ_SUFFIX)
                .partitions(TOPIC_DLQ_PARTITIONS)
                .replicas(1)
                .config("retention.ms", String.valueOf(30 * 24 * 60 * 60 * 1000L))
                .config("cleanup.policy", "delete")
                .build();
    }
}
