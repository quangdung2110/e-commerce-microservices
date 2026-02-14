package com.example.common.config.kafka;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@ConfigurationProperties(prefix = "kafka")
@Data
public class KafkaProperties {

    private String bootstrapServers = "localhost:9092";
    private Consumer consumer = new Consumer();
    private Producer producer = new Producer();
    private List<TopicConfig> topics = new ArrayList<>();

    @Data
    public static class TopicConfig {
        private String name;
        private int partitions = 3;
        private short replicationFactor = 1;
    }

    @Data
    public static class Producer {
        private int retries = 3;
        private String acks = "all";
        private boolean enableIdempotence = true;
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
