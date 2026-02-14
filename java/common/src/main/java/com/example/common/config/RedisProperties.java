package com.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "redis")
public class RedisProperties {
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private int database = 0;
    private int timeout = 3000;
    
    // Cache TTL configurations (in seconds)
    private CacheTTL cacheTtl = new CacheTTL();
    
    @Data
    public static class CacheTTL {
        private long cart = 7200; // 2 hours
        private long promotion = 300; // 5 minutes
        private long promoValidation = 60; // 1 minute
        private long session = 1800; // 30 minutes
        private long idempotencyKey = 86400; // 24 hours
    }
}
