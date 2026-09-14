package com.platform.promotionservice.domain;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ValidityMode;
import com.platform.promotionservice.domain.pricing.DiscountMath;
import com.platform.promotionservice.domain.pricing.ValidityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Domain unit tests: discount math (PROMO-BR-003), phase [start,end), validity modes (PROMO-BR-008). */
class DomainRulesTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-09-05T10:00:00Z");

    @Test
    @DisplayName("percentage uses basis points, floors, caps by max_discount and clamps by subtotal")
    void percentage() {
        assertThat(DiscountMath.discount(DiscountType.PERCENTAGE, 1000, null, 350000)).isEqualTo(35000);
        assertThat(DiscountMath.discount(DiscountType.PERCENTAGE, 1000, 20000L, 350000)).isEqualTo(20000);
        assertThat(DiscountMath.discount(DiscountType.PERCENTAGE, 3333, null, 100)).isEqualTo(33);
        assertThat(DiscountMath.discount(DiscountType.PERCENTAGE, 10000, null, 999)).isEqualTo(999);
        assertThat(DiscountMath.discount(DiscountType.PERCENTAGE, 1000, null, 0)).isZero();
    }

    @Test
    @DisplayName("fixed clamps to subtotal; final never negative")
    void fixed() {
        assertThat(DiscountMath.discount(DiscountType.FIXED_VND, 100000, null, 350000)).isEqualTo(100000);
        assertThat(DiscountMath.discount(DiscountType.FIXED_VND, 100000, null, 50000)).isEqualTo(50000);
        assertThat(DiscountMath.finalAmount(50000, 50000)).isZero();
        assertThat(DiscountMath.finalAmount(350000, 100000)).isEqualTo(250000);
    }

    @Test
    @DisplayName("phase derived from publication_status and half-open [start,end)")
    void phases() {
        CampaignEntity campaign = campaign(PublicationStatus.PUBLISHED);
        assertThat(campaign.phaseAt(START.minusMillis(1))).isEqualTo(CampaignPhase.UPCOMING);
        assertThat(campaign.phaseAt(START)).isEqualTo(CampaignPhase.ACTIVE);
        assertThat(campaign.phaseAt(END.minusMillis(1))).isEqualTo(CampaignPhase.ACTIVE);
        assertThat(campaign.phaseAt(END)).isEqualTo(CampaignPhase.ENDED);
        assertThat(campaign(PublicationStatus.DRAFT).phaseAt(START)).isEqualTo(CampaignPhase.DRAFT);
        assertThat(campaign(PublicationStatus.PAUSED).phaseAt(START)).isEqualTo(CampaignPhase.PAUSED);
        assertThat(campaign(PublicationStatus.CANCELLED).phaseAt(START)).isEqualTo(CampaignPhase.CANCELLED);
        assertThat(campaign.etag()).isEqualTo("\"1\"");
    }

    @Test
    @DisplayName("validity snapshot: CAMPAIGN_END, DURATION_AFTER_SAVE, FIXED_DATE — no implicit default")
    void validity() {
        CampaignEntity campaign = campaign(PublicationStatus.PUBLISHED);
        Instant savedAt = Instant.parse("2026-09-02T00:00:00Z");

        PromotionEntity campaignEnd = new PromotionEntity();
        campaignEnd.setValidityMode(ValidityMode.CAMPAIGN_END);
        assertThat(ValidityPolicy.expiryFor(campaignEnd, campaign, savedAt)).isEqualTo(END);

        PromotionEntity duration = new PromotionEntity();
        duration.setValidityMode(ValidityMode.DURATION_AFTER_SAVE);
        duration.setValidForSeconds(3600L);
        assertThat(ValidityPolicy.expiryFor(duration, campaign, savedAt)).isEqualTo(savedAt.plusSeconds(3600));

        PromotionEntity fixed = new PromotionEntity();
        fixed.setValidityMode(ValidityMode.FIXED_DATE);
        fixed.setRedeemUntil(Instant.parse("2026-12-31T23:59:59Z"));
        assertThat(ValidityPolicy.expiryFor(fixed, campaign, savedAt)).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
    }

    private static CampaignEntity campaign(PublicationStatus status) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setPublicationStatus(status);
        campaign.setStartTime(START);
        campaign.setEndTime(END);
        return campaign;
    }
}
