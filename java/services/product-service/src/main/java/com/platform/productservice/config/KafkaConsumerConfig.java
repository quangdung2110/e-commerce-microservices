package com.platform.productservice.config;

import com.platform.productservice.infrastructure.kafka.MarkdownSnapshotConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
 * Consumer cho markdown snapshot của Promotion.
 *
 * <p>Ack theo record sau khi listener trả về, nghĩa là sau khi transaction DB đã
 * commit. Retry có backoff; hết retry thì message sang DLQ theo
 * {@code x-retry-policy} của AsyncAPI.
 *
 * <p>Producer duy nhất ở đây phục vụ DLQ. Event nghiệp vụ không bao giờ được
 * publish trực tiếp: chúng đi qua outbox và Debezium (ADR-012).
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> markdownConsumerFactory(KafkaProperties kafkaProperties,
                                                                   ProductProperties productProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, productProperties.getMarkdown().getGroupId());
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
    public ConcurrentKafkaListenerContainerFactory<String, String> markdownListenerContainerFactory(
            ConsumerFactory<String, String> markdownConsumerFactory,
            KafkaTemplate<String, String> deadLetterKafkaTemplate,
            ProductProperties productProperties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(markdownConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(markdownErrorHandler(deadLetterKafkaTemplate, productProperties));
        return factory;
    }

    private DefaultErrorHandler markdownErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                     ProductProperties productProperties) {
        String dlqTopic = productProperties.getMarkdown().getDlqTopic();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic, record.partition()));

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(productProperties.getMarkdown().getRetryAttempts());
        backOff.setInitialInterval(productProperties.getMarkdown().getRetryBackoff().toMillis());
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Event sai hợp đồng không thể tự khỏi khi retry.
        handler.addNotRetryableExceptions(MarkdownSnapshotConsumer.NonRetryableEventException.class);
        return handler;
    }
}
