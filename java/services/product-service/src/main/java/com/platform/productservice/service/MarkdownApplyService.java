package com.platform.productservice.service;

import com.platform.productservice.config.ProductProperties;
import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.enums.MarkdownState;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.domain.pricing.MarkdownPricing;
import com.platform.productservice.infrastructure.persistence.MarkdownProjectionRepository;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.service.support.ProductWriteSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Apply markdown snapshot của Promotion (WF-PRODUCT-005).
 *
 * <p>Snapshot cũ hoặc trùng {@code source_revision} là no-op (PRODUCT-BR-007).
 * Product không có scheduled safety check: Promotion sở hữu lịch và phải phát
 * {@code ACTIVE}/{@code INACTIVE} đúng thời điểm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkdownApplyService {

    private final ProductRepository productRepository;
    private final ProductMediaRepository mediaRepository;
    private final MarkdownProjectionRepository markdownRepository;
    private final ProductWriteSupport writeSupport;
    private final ProductProperties properties;

    /** Snapshot đã parse từ CloudEvent {@code product-markdown-snapshot.v1}. */
    public record Snapshot(UUID productId,
                           Long promotionRuleId,
                           long sourceRevision,
                           MarkdownState state,
                           DiscountType discountType,
                           Integer discountBps,
                           Long fixedDiscountVnd,
                           Long maxDiscountVnd,
                           Instant startsAt,
                           Instant endsAt,
                           String inactiveReason) {

        /** Tombstone INACTIVE: chỉ có reason (contracts/events/promotion/v1). */
        public static Snapshot inactive(UUID productId, long sourceRevision, String inactiveReason) {
            return new Snapshot(productId, null, sourceRevision, MarkdownState.INACTIVE,
                    null, null, null, null, null, null, inactiveReason);
        }
    }

    public enum Outcome {
        /** Đã reprice và phát catalog event mới. */
        APPLIED,
        /** Revision mới nhưng giá không đổi: chỉ advance source_revision. */
        RECORDED,
        /** Revision cũ/trùng hoặc Product không còn tồn tại. */
        IGNORED
    }

    @Transactional
    public Outcome apply(Snapshot snapshot) {
        Instant now = Instant.now();
        writeSupport.applyLockTimeout(properties.getMarkdown().getLockTimeout());

        ProductEntity product = productRepository.lockById(snapshot.productId()).orElse(null);
        if (product == null) {
            // Product đã bị hard delete; tombstone đã phát nên snapshot không còn ý nghĩa.
            log.info("Markdown snapshot ignored, product {} no longer exists", snapshot.productId());
            return Outcome.IGNORED;
        }
        List<SkuEntity> skus = writeSupport.lockSkus(snapshot.productId());

        MarkdownProjectionEntity projection = markdownRepository.findById(snapshot.productId()).orElse(null);
        if (projection != null && snapshot.sourceRevision() <= projection.getSourceRevision()) {
            log.debug("Markdown snapshot ignored for product {}: source_revision {} <= {}",
                    snapshot.productId(), snapshot.sourceRevision(), projection.getSourceRevision());
            return Outcome.IGNORED;
        }

        boolean isNewProjection = projection == null;
        if (isNewProjection) {
            projection = new MarkdownProjectionEntity();
            projection.setProductId(snapshot.productId());
        }
        MarkdownState effective = MarkdownPricing.effectiveState(snapshot.state(), snapshot.endsAt(), now);
        projection.setSourceRevision(snapshot.sourceRevision());
        projection.setState(effective);
        if (effective == MarkdownState.ACTIVE) {
            projection.setPromotionRuleId(snapshot.promotionRuleId());
            projection.setDiscountType(snapshot.discountType());
            projection.setDiscountBps(snapshot.discountBps());
            projection.setFixedDiscountVnd(snapshot.fixedDiscountVnd());
            projection.setMaxDiscountVnd(snapshot.maxDiscountVnd());
            projection.setStartsAt(snapshot.startsAt());
            projection.setEndsAt(snapshot.endsAt());
            projection.setInactiveReason(null);
        } else {
            // Tombstone (docs/product/data.md §3.3): clear rule configuration; ACTIVE đến muộn
            // sau ends_at materialize thành INACTIVE/EXPIRED.
            projection.setPromotionRuleId(null);
            projection.setDiscountType(null);
            projection.setDiscountBps(null);
            projection.setFixedDiscountVnd(null);
            projection.setMaxDiscountVnd(null);
            projection.setStartsAt(null);
            projection.setEndsAt(null);
            projection.setInactiveReason(snapshot.inactiveReason() == null ? "EXPIRED" : snapshot.inactiveReason());
        }
        projection.setReceivedAt(now);
        if (isNewProjection) {
            writeSupport.persist(projection);
        }

        List<SkuEntity> repriced = new ArrayList<>();
        for (SkuEntity sku : skus) {
            long sellingPrice = MarkdownPricing.sellingPrice(sku.getBasePriceVnd(), projection);
            if (sellingPrice != sku.getSellingPriceVnd()) {
                sku.setSellingPriceVnd(sellingPrice);
                sku.setUpdatedAt(now);
                repriced.add(sku);
            }
        }
        if (repriced.isEmpty()) {
            return Outcome.RECORDED;
        }

        product.bumpRevision(now);
        List<ProductMediaEntity> media =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(snapshot.productId());
        SkuChangeKind changeKind = projection.getState() == MarkdownState.ACTIVE
                ? SkuChangeKind.MARKDOWN_REPRICED
                : SkuChangeKind.MARKDOWN_EXPIRED;
        writeSupport.emitSkuSnapshots(product, repriced, media, changeKind, now);
        return Outcome.APPLIED;
    }
}
