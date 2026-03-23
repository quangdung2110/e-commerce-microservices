package com.ecommerce.promotion.infrastructure.config;

import com.ecommerce.promotion.domain.entity.MarkdownRule;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.enums.MarkdownRuleStatus;
import com.ecommerce.promotion.domain.repository.MarkdownRuleRepository;
import com.ecommerce.promotion.domain.repository.PromotionRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine L1 Cache Configuration.
 * Theo thiết kế redis_caching_design.md - Section 2.1:
 *   - refreshAfterWrite(1, MINUTES) — anti-Thundering Herd
 *   - CacheLoader.reload() trả về CompletableFuture → non-blocking refresh
 *   - Khi Pub/Sub PROMO_EVICT nhận → Caffeine invalidate → next get() triggers reload
 *
 * QUAN TRỌNG: Dùng refreshAfterWrite, KHÔNG dùng expireAfterWrite.
 * Lý do: refreshAfterWrite trả stale data ngay lập tức + async reload background.
 *         expireAfterWrite block tất cả request chờ reload (Thundering Herd).
 */
@Configuration
public class CaffeineCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CaffeineCacheConfig.class);

    @Value("${promotion.cache.caffeine.refresh-after-write-seconds:60}")
    private long refreshAfterWriteSeconds;

    @Value("${promotion.cache.caffeine.maximum-size:10000}")
    private long maximumSize;

    /**
     * Promotion Cache — keyed by promotionId.
     * CacheLoader load() → query database.
     * CacheLoader reload() → return CompletableFuture (async, non-blocking).
     *
     * Theo thiết kế redis_caching_design.md - Section 1: Multi-Level Caching
     *   L1 (Caffeine) → L2 (Redis) → L3 (PostgreSQL)
     */
    @Bean
    public LoadingCache<Long, Optional<Promotion>> promotionCache(PromotionRepository promotionRepository) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .refreshAfterWrite(refreshAfterWriteSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build(promotionId -> {
                    log.debug("Caffeine cache miss → loading promotion from DB: id={}", promotionId);
                    return promotionRepository.findById(promotionId);
                });
    }

    /**
     * Active Markdown Rules Cache — keyed by constant key "ACTIVE_RULES".
     * Load all active markdown rules in 1 query.
     * refreshAfterWrite ensures background reload → no Thundering Herd.
     *
     * Theo thiết kế redis_caching_design.md - Section 2.6: Markdown Cache
     */
    @Bean
    public LoadingCache<String, List<MarkdownRule>> markdownRulesCache(MarkdownRuleRepository markdownRuleRepository) {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .refreshAfterWrite(refreshAfterWriteSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build(key -> {
                    log.debug("Caffeine markdown rules cache miss → loading from DB");
                    return markdownRuleRepository.findByStatus(MarkdownRuleStatus.ACTIVE);
                });
    }
}
