package com.platform.promotionservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis runtime store. Dùng {@code spring.data.redis.*} của Boot (standalone
 * trong docker-compose, cluster ở production); mọi multi-key Lua theo promotion
 * dùng hash tag {@code {promotion_id}} nên cùng cluster slot (ADR-015).
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
