package com.example.common.config.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import com.example.common.event.BaseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaConfig {
    
    /**
     * ObjectMapper cho JSON serialization/deserialization
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Producer Factory cho Kafka
     */
    @Bean
    public ProducerFactory<String, BaseEvent> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        configProps.put(ProducerConfig.ACKS_CONFIG, kafkaProperties.getProducer().getAcks()); // Chờ tất cả broker xác nhận đã nhận tin
        configProps.put(ProducerConfig.RETRIES_CONFIG, kafkaProperties.getProducer().getRetries()); // Số lần thử lại khi gửi thất bại
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, kafkaProperties.getProducer().isEnableIdempotence()); // Bật idempotence để tránh trùng lặp tin
        
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Tạo JsonSerializer với ObjectMapper custom
        JsonSerializer<BaseEvent> jsonSerializer = new JsonSerializer<>(objectMapper());
        jsonSerializer.setAddTypeInfo(false);   // Quan trọng: Dùng polymorphic trong JSON, không dùng Header
        
        return new DefaultKafkaProducerFactory<>(configProps, new StringSerializer(), jsonSerializer);
    }

    /**
     * Consumer Factory cho Kafka
     */
    @Bean
    public ConsumerFactory<String, BaseEvent> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumer().getGroupId());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaProperties.getConsumer().getAutoOffsetReset());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, kafkaProperties.getConsumer().isEnableAutoCommit());

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // JsonDeserializer configuration
        JsonDeserializer<BaseEvent> jsonDeserializer = new JsonDeserializer<>(BaseEvent.class, objectMapper());
        jsonDeserializer.addTrustedPackages("com.example.*");
        jsonDeserializer.setUseTypeHeaders(false);
        
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), jsonDeserializer);
    }

    /**
     * Kafka Listener Container Factory
     * Cấu hình cho @KafkaListener
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BaseEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, BaseEvent> consumerFactory,
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, BaseEvent> template) {
        
        ConcurrentKafkaListenerContainerFactory<String, BaseEvent> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(kafkaProperties.getConsumer().getConcurrency());
        
        // Manual commit mode - commit sau khi xử lý thành công
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        
        // Error handling - không retry, gửi thẳng vào DLT
        // Cấu hình Tự động gửi vào DLT khi có lỗi sau 3 lần thử 
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(template, (record, ex) -> 
                new TopicPartition(record.topic() + kafkaProperties.getConsumer().getDeadLetterTopicSuffix(), -1)),
            new FixedBackOff(1000L, 2) // Thử lại 2 lần, mỗi lần cách nhau 1s trước khi ném vào DLT
        );
        factory.setCommonErrorHandler(errorHandler); 
        
        return factory;
    }

    /**
     * Kafka Template cho cả Producer và Consumer
     */
    @Bean
    public KafkaTemplate<String, BaseEvent> kafkaTemplate(ProducerFactory<String, BaseEvent> factory) {
        return new KafkaTemplate<>(factory);
    }
}
