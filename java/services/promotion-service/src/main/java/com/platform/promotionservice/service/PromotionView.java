package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ValidityMode;

import java.time.Instant;

/**
 * Snapshot JSON của một promotion + campaign fence dùng cho discovery/calculate
 * (ADR-002). {@code cacheVersion} là version fence toàn cục lúc ghi.
 */
public record PromotionView(
        long promotionId,
        long campaignId,
        long campaignRevision,
        PublicationStatus publicationStatus,
        Instant startTime,
        Instant endTime,
        String code,
        DistributionMode distributionMode,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountVnd,
        long minOrderAmountVnd,
        String conditionsRule,
        int ruleSchemaVersion,
        ValidityMode validityMode,
        Long validForSeconds,
        Instant redeemUntil,
        boolean enabled,
        long cacheVersion) {

    public static PromotionView of(PromotionEntity promotion, CampaignEntity campaign, long cacheVersion) {
        return new PromotionView(
                promotion.getId(), campaign.getId(), campaign.getRevision(), campaign.getPublicationStatus(),
                campaign.getStartTime(), campaign.getEndTime(), promotion.getCode(),
                promotion.getDistributionMode(), promotion.getDiscountType(), promotion.getDiscountValue(),
                promotion.getMaxDiscountVnd(), promotion.getMinOrderAmountVnd(), promotion.getConditionsRule(),
                promotion.getRuleSchemaVersion(), promotion.getValidityMode(), promotion.getValidForSeconds(),
                promotion.getRedeemUntil(), promotion.isEnabled(), cacheVersion);
    }

    public CampaignPhase phaseAt(Instant now) {
        return switch (publicationStatus) {
            case DRAFT -> CampaignPhase.DRAFT;
            case PAUSED -> CampaignPhase.PAUSED;
            case CANCELLED -> CampaignPhase.CANCELLED;
            case PUBLISHED -> now.isBefore(startTime) ? CampaignPhase.UPCOMING
                    : now.isBefore(endTime) ? CampaignPhase.ACTIVE : CampaignPhase.ENDED;
        };
    }
}
