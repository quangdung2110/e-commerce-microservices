package com.ecommerce.shipping.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Theo thiết kế shipping_workflow.md mục 2 và shipping_workers.md mục 4: idempotency + delayed dedupe keys.
@Configuration
public class RedisConfig {

	@Bean
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
		return new StringRedisTemplate(redisConnectionFactory);
	}
}
