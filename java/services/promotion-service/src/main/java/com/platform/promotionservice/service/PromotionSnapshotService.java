package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.rule.RuleNode;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.SnapshotCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache-aside snapshot: version fence + JSON theo promotion; miss đọc PostgreSQL.
 * AST được parse request-scoped từ JSON, không cache object trong JVM (ADR-002).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionSnapshotService {

    private final SnapshotCache cache;
    private final PromotionRepository promotions;
    private final CampaignRepository campaigns;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<PromotionView> find(long promotionId) {
        long version = cache.currentVersion();
        if (version >= 0) {
            Optional<PromotionView> cached = cache.get(promotionId).flatMap(this::parse)
                    .filter(view -> view.cacheVersion() == version);
            if (cached.isPresent()) {
                return cached;
            }
        }
        Optional<PromotionEntity> promotion = promotions.findById(promotionId);
        if (promotion.isEmpty()) {
            return Optional.empty();
        }
        CampaignEntity campaign = campaigns.findById(promotion.get().getCampaignId()).orElseThrow();
        PromotionView view = PromotionView.of(promotion.get(), campaign, Math.max(version, 0));
        if (version >= 0) {
            write(view);
        }
        return Optional.of(view);
    }

    public RuleNode parseRule(String json) {
        try {
            return objectMapper.readValue(json, RuleNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored conditions_rule is not a valid AST", e);
        }
    }

    /** One version read and a snapshot pipeline per batch; all misses use one DB projection query. */
    @Transactional(readOnly = true)
    public Map<Long, PromotionView> findAll(List<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = promotionIds.stream().distinct().toList();
        long version = cache.currentVersion();
        Map<Long, PromotionView> result = new LinkedHashMap<>();
        if (version >= 0) {
            cache.getAll(ids).forEach((id, json) -> parse(json)
                    .filter(view -> view.cacheVersion() == version && view.promotionId() == id)
                    .ifPresent(view -> result.put(id, view)));
        }
        List<Long> missing = ids.stream().filter(id -> !result.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Map<Long, String> writes = new LinkedHashMap<>();
            for (PromotionView view : promotions.findSnapshotViews(missing, Math.max(version, 0))) {
                result.put(view.promotionId(), view);
                if (version >= 0) {
                    try {
                        writes.put(view.promotionId(), objectMapper.writeValueAsString(view));
                    } catch (Exception e) {
                        log.debug("Snapshot serialization failed: {}", e.getMessage());
                    }
                }
            }
            if (!writes.isEmpty()) {
                cache.putAll(writes);
            }
        }
        return result;
    }

    /** Sau commit admin mutation: evict snapshot của mọi promotion trong campaign + bump fence. */
    public void invalidateCampaign(long campaignId) {
        try {
            promotions.findByCampaignIdOrderByIdAsc(campaignId).forEach(p -> cache.evict(p.getId()));
            cache.bumpVersion();
        } catch (RuntimeException e) {
            // Không fail-open mutation đã commit: snapshot stale tối đa đến TTL (ADR-002).
            log.warn("Snapshot invalidation failed for campaign {}: {}", campaignId, e.getMessage());
        }
    }

    private Optional<PromotionView> parse(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, PromotionView.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void write(PromotionView view) {
        try {
            cache.put(view.promotionId(), objectMapper.writeValueAsString(view));
        } catch (Exception e) {
            log.debug("Snapshot serialization failed: {}", e.getMessage());
        }
    }
}
