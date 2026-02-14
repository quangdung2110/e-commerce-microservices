package com.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Kafka Configuration Properties
 * Supports multiple topics per service
 */
@Data
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {
    
    private String bootstrapServers = "localhost:9092";
    
    // Topic configurations
    private TopicConfig topic = new TopicConfig();
    
    // Consumer configurations
    private Consumer consumer = new Consumer();
    
    @Data
    public static class TopicConfig {
        private List<Topic> topics = new ArrayList<>();
    }
    
    @Data
    public static class Topic {
        private String name;
        private int partitions = 3;
        private int replicationFactor = 1;
    }
    
    @Data
    public static class Consumer {
        private String groupId = "default-group";
        private String autoOffsetReset = "earliest";
        private boolean enableAutoCommit = false;
        private int concurrency = 3;
        private String deadLetterTopicSuffix = "-dlt";
    }
}
