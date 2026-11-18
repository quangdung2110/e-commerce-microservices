package com.platform.promotionservice.config;

import com.platform.promotionservice.infrastructure.kafka.ProductDeletedConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer duy nhất của Promotion: Product tombstone {@code product-deleted.v1}
 * trên {@code t.product.events} (WF-PROMO-009). Ack theo record sau khi listener
 * trả về, tức sau khi transaction DB đã commit. Producer duy nhất phục vụ DLQ;
 * event nghiệp vụ không bao giờ publish trực tiếp (ADR-016).
 */
@Configuration
@ConditionalOnProperty(prefix = "promotion.kafka", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> productTombstoneConsumerFactory(KafkaProperties kafkaProperties,
                                                                           PromotionProperties properties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getGroupId());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ProducerFactory<String, String> deadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> deadLetterKafkaTemplate(
            ProducerFactory<String, String> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> productTombstoneListenerContainerFactory(
            ConsumerFactory<String, String> productTombstoneConsumerFactory,
            KafkaTemplate<String, String> deadLetterKafkaTemplate,
            PromotionProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(productTombstoneConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler(deadLetterKafkaTemplate, properties));
        return factory;
    }

    private DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                             PromotionProperties properties) {
        String dlqTopic = properties.getKafka().getProductDlqTopic();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic, record.partition()));
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(properties.getKafka().getRetryAttempts());
        backOff.setInitialInterval(properties.getKafka().getRetryBackoff().toMillis());
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(ProductDeletedConsumer.NonRetryableEventException.class);
        return handler;
    }
}
