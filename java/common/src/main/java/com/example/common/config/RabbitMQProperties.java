package com.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "rabbitmq")
public class RabbitMQProperties {
    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest";
    private String virtualHost = "/";
    
    // Queue configurations for different services
    private Map<String, QueueConfig> queues = new HashMap<>();
    
    @Data
    public static class QueueConfig {
        private String name;
        private boolean durable = true;
        private String exchange = "";
        private String routingKey = "";
        private int maxRetry = 3;
        private long retryDelay = 5000; // 5 seconds
    }
}
