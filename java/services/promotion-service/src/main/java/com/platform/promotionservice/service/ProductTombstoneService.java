package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.entity.CatalogProductTombstoneEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.InvalidationReason;
import com.platform.promotionservice.infrastructure.persistence.CatalogProductTombstoneRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WF-PROMO-009: persist terminal Product watermark (kể cả khi chưa có rule) và
 * invalidate markdown rule idempotently theo catalog revision. Cùng advisory lock với
 * admin markdown mutation; không bump campaign revision/claim gate; không phát
 * INACTIVE ngược lại Product đã xóa. Cache invalidation product-scoped chạy sau commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductTombstoneService {

    private final CatalogProductTombstoneRepository tombstones;
    private final MarkdownRuleRepository rules;
    private final PromotionJdbc jdbc;

    public record Outcome(long watermark, int invalidatedRules, boolean watermarkAdvanced) {
    }

    @Transactional
    public Outcome apply(UUID productId, long catalogRevision, Instant deletedAt) {
        jdbc.lockProduct(productId);
        Instant now = jdbc.dbNow();
        Optional<CatalogProductTombstoneEntity> existing = tombstones.findById(productId);
        long watermark;
        boolean advanced = false;
        if (existing.isEmpty()) {
            CatalogProductTombstoneEntity tombstone = new CatalogProductTombstoneEntity();
            tombstone.setProductId(productId);
            tombstone.setSourceProductRevision(catalogRevision);
            tombstone.setProductDeletedAt(deletedAt);
            tombstone.setReceivedAt(now);
            tombstones.save(tombstone);
            watermark = catalogRevision;
            advanced = true;
        } else if (catalogRevision > existing.get().getSourceProductRevision()) {
            existing.get().setSourceProductRevision(catalogRevision);
            existing.get().setProductDeletedAt(deletedAt);
            existing.get().setReceivedAt(now);
            watermark = catalogRevision;
            advanced = true;
        } else {
            // Duplicate/stale: không lùi watermark nhưng vẫn repair rule chưa invalidated.
            watermark = existing.get().getSourceProductRevision();
        }

        int invalidated = 0;
        List<MarkdownRuleEntity> affected = rules.lockActiveByProduct(productId);
        for (MarkdownRuleEntity rule : affected) {
            if (rule.getSourceProductRevision() == null || rule.getSourceProductRevision() < watermark) {
                rule.setInvalidatedAt(now);
                rule.setInvalidationReason(InvalidationReason.PRODUCT_DELETED);
                rule.setSourceProductRevision(watermark);
                rule.setUpdatedAt(now);
                invalidated++;
            }
        }
        log.info("Product tombstone product={} catalog_revision={} watermark={} invalidated_rules={}",
                productId, catalogRevision, watermark, invalidated);
        return new Outcome(watermark, invalidated, advanced);
    }
}
