package com.ecommerce.promotion.infrastructure.listener;

import com.ecommerce.promotion.domain.entity.MarkdownRule;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Redis Pub/Sub Listener — Cache Eviction events.
 * Theo thiết kế redis_caching_design.md - Section 2.1.
 *
 * Subscribe channel: channel:promo:cache:evict
 *
 * Message formats:
 * - "PROMO_EVICT:{promotionId}" → Caffeine invalidate promotion → next get() triggers refreshAfterWrite
 * - "MARKDOWN_EVICT"            → Caffeine invalidate markdown rules → next get() triggers refreshAfterWrite
 *
 * QUAN TRỌNG: invalidate() (không phải remove) → next get() kích hoạt CacheLoader.reload()
 *             trả stale data ngay + async reload background (anti-Thundering Herd).
 */
@Component
public class RedisCacheEvictionListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheEvictionListener.class);

    private final LoadingCache<Long, Optional<Promotion>> promotionCache;
    private final LoadingCache<String, List<MarkdownRule>> markdownRulesCache;

    public RedisCacheEvictionListener(
            LoadingCache<Long, Optional<Promotion>> promotionCache,
            LoadingCache<String, List<MarkdownRule>> markdownRulesCache) {
        this.promotionCache = promotionCache;
        this.markdownRulesCache = markdownRulesCache;
    }

    /**
     * Nhận Redis Pub/Sub message → invalidate Caffeine cache entry.
     * Caffeine với refreshAfterWrite sẽ tự động reload non-blocking.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody());
        log.debug("Received cache eviction message: {}", body);

        try {
            if (body.startsWith("PROMO_EVICT:")) {
                String idStr = body.substring("PROMO_EVICT:".length());
                Long promotionId = Long.parseLong(idStr);

                // invalidate → next get() triggers CacheLoader.reload() async
                promotionCache.invalidate(promotionId);
                log.info("Caffeine promotion cache invalidated: promotionId={}", promotionId);

            } else if (body.equals("MARKDOWN_EVICT")) {
                // Invalidate markdown rules cache
                markdownRulesCache.invalidate("ACTIVE_RULES");
                log.info("Caffeine markdown rules cache invalidated");

            } else {
                log.warn("Unknown cache eviction message: {}", body);
            }
        } catch (NumberFormatException e) {
            log.error("Failed to parse promotionId from eviction message: {}", body, e);
        }
    }
}
