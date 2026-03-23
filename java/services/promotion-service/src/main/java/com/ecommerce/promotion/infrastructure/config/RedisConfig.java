package com.ecommerce.promotion.infrastructure.config;

import com.ecommerce.promotion.infrastructure.adapter.CacheEvictionAdapter;
import com.ecommerce.promotion.infrastructure.listener.RedisCacheEvictionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Configuration.
 * Theo thiết kế redis_caching_design.md - Section 2.
 *
 * - StringRedisTemplate: Lua Script execution, Rate Limit, TTL keys
 * - Redis Pub/Sub Listener Container: Cache Eviction Channel
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate — sử dụng StringSerializer cho cả key và value.
     * Phù hợp cho Lua Script execution (tất cả KEYS và ARGV đều là String).
     * Spring Boot auto-config đã tạo sẵn, nhưng khai báo explicit cho rõ ràng.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Redis Pub/Sub MessageListenerAdapter — delegate tới RedisCacheEvictionListener.
     */
    @Bean
    public MessageListenerAdapter cacheEvictionListenerAdapter(RedisCacheEvictionListener listener) {
        return new MessageListenerAdapter(listener, "onMessage");
    }

    /**
     * Redis Pub/Sub ChannelTopic — channel:promo:cache:evict.
     * Theo thiết kế redis_caching_design.md - Section 2.1.
     */
    @Bean
    public ChannelTopic cacheEvictTopic() {
        return new ChannelTopic(CacheEvictionAdapter.CACHE_EVICT_CHANNEL);
    }

    /**
     * Redis Pub/Sub Listener Container — subscribe tất cả cache eviction events.
     * Mỗi instance Java đều subscribe → đảm bảo tất cả Caffeine cache được sync.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter cacheEvictionListenerAdapter,
            ChannelTopic cacheEvictTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheEvictionListenerAdapter, cacheEvictTopic);
        return container;
    }
}
