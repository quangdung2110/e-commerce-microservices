package com.platform.promotionservice.domain.pricing;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;

import java.time.Instant;

/**
 * Snapshot {@code wallet.expired_at} một lần lúc save/claim/grant (data.md §3.2).
 * Không có mặc định ngầm 30 ngày (PROMO-BR-008).
 */
public final class ValidityPolicy {

    private ValidityPolicy() {
    }

    public static Instant expiryFor(PromotionEntity promotion, CampaignEntity campaign, Instant savedAt) {
        return switch (promotion.getValidityMode()) {
            case CAMPAIGN_END -> campaign.getEndTime();
            case DURATION_AFTER_SAVE -> savedAt.plusSeconds(promotion.getValidForSeconds());
            case FIXED_DATE -> promotion.getRedeemUntil();
        };
    }
}
