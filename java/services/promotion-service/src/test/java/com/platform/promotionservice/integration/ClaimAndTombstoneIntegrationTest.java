package com.platform.promotionservice.integration;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.infrastructure.redis.ClaimStore;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.service.ClaimService;
import com.platform.promotionservice.service.ProductTombstoneService;
import com.platform.promotionservice.service.RuntimeRebuildService;
import com.platform.promotionservice.web.dto.CampaignDtos;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MarkdownDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-PROMO-003 limited claim, WF-PROMO-009 Product tombstone và clone identity. */
class ClaimAndTombstoneIntegrationTest extends AbstractPromotionIntegrationTest {

    @Autowired
    ClaimService claims;
    @Autowired
    ClaimStore claimStore;
    @Autowired
    RuntimeStore runtime;
    @Autowired
    RuntimeRebuildService runtimeRebuild;
    @Autowired
    ProductTombstoneService tombstones;

    @Test
    @DisplayName("limited claim: pool pre-generated at publish, gate revision fence, DB commit before success, replay")
    void limitedClaim() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, 2L);
        CampaignEntity published = publish(campaign);
        long revision = published.getRevision();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM coupon_claims WHERE promotion_id = ?", Long.class,
                promotion.getId())).isEqualTo(2);
        assertThat(claimStore.poolSize(promotion.getId())).isEqualTo(2);
        RuntimeStore.RuntimeState gate = runtime.read(promotion.getId()).orElseThrow();
        assertThat(gate.claimEnabled()).isTrue();
        assertThat(gate.claimRevision()).isEqualTo(revision);
        assertThat(gate.closesAtMs()).isEqualTo(published.getEndTime().toEpochMilli());

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();

        assertThatThrownBy(() -> claims.claim(alice, promotion.getId(), revision - 1))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.CLAIM_GATE_STALE);

        ClaimService.Outcome first = claims.claim(alice, promotion.getId(), revision);
        assertThat(first).isInstanceOf(ClaimService.Committed.class);
        ClaimService.Committed committed = (ClaimService.Committed) first;
        assertThat(committed.created()).isTrue();
        assertThat(committed.response().campaignRevisionAtAcquisition()).isEqualTo(revision);
        assertThat(committed.response().wallet().status().name()).isEqualTo("SAVED");
        assertThat(committed.response().wallet().claimId()).isEqualTo(committed.response().claimId());
        assertThat(claimStore.pending(promotion.getId(), Long.parseLong(committed.response().claimId()))).isEmpty();

        ClaimService.Outcome replay = claims.claim(alice, promotion.getId(), revision);
        assertThat(((ClaimService.Committed) replay).created()).isFalse();
        assertThat(((ClaimService.Committed) replay).response().claimId()).isEqualTo(committed.response().claimId());

        assertThat(((ClaimService.Committed) claims.claim(bob, promotion.getId(), revision)).created()).isTrue();
        assertThatThrownBy(() -> claims.claim(carol, promotion.getId(), revision))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);

        Map<String, Object> rows = jdbcTemplate.queryForMap("""
                SELECT (SELECT count(*) FROM coupon_claims WHERE promotion_id = ? AND status = 'CLAIMED') AS claimed,
                       (SELECT claimed_quantity FROM promotion_budgets WHERE promotion_id = ?) AS counter,
                       (SELECT count(*) FROM user_coupon_wallets WHERE promotion_id = ?) AS wallets
                """, promotion.getId(), promotion.getId(), promotion.getId());
        assertThat(rows).containsEntry("claimed", 2L).containsEntry("counter", 2L).containsEntry("wallets", 2L);

        // Pause closes the gate conservatively before commit; claims after that are rejected.
        transition(published, PublicationStatus.PAUSED);
        assertThat(runtime.read(promotion.getId()).orElseThrow().claimEnabled()).isFalse();
        UUID dave = UUID.randomUUID();
        assertThatThrownBy(() -> claims.claim(dave, promotion.getId(), revision + 1))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.CAMPAIGN_NOT_ACTIVE);
    }

    @Test
    @DisplayName("Redis loss: runtime claim fields and pool rebuild from PostgreSQL, pending is swept via the same path")
    void redisLossRebuildAndSweeper() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, 3L);
        CampaignEntity published = publish(campaign);
        UUID alice = UUID.randomUUID();
        claims.claim(alice, promotion.getId(), published.getRevision());

        redis.delete(List.of(
                com.platform.promotionservice.infrastructure.redis.PromotionRedisKeys.runtime(promotion.getId()),
                com.platform.promotionservice.infrastructure.redis.PromotionRedisKeys.claimPool(promotion.getId())));
        UUID bob = UUID.randomUUID();
        // GATE_MISSING → rebuild inline → acquisition succeeds
        assertThat(((ClaimService.Committed) claims.claim(bob, promotion.getId(), published.getRevision())).created()).isTrue();
        assertThat(claimStore.poolSize(promotion.getId())).isEqualTo(1);

        // Sweeper: simulate a crash after SPOP by acquiring via Lua only, then sweep after the stale threshold.
        UUID carol = UUID.randomUUID();
        ClaimStore.AcquireResult acquired = claimStore.acquire(promotion.getId(), carol, published.getRevision(), 0);
        assertThat(acquired.code()).isEqualTo(ClaimStore.AcquireCode.ACQUIRED);
        assertThat(claims.sweepPending(promotion.getId(), Instant.now().plusSeconds(1), 10)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_coupon_wallets WHERE user_id = ?", Long.class,
                carol)).isEqualTo(1);
        assertThat(claimStore.pending(promotion.getId(), acquired.claimId())).isEmpty();
    }

    @Test
    @DisplayName("Product tombstone: watermark persisted, rules invalidated, mutation rejected, no reverse snapshot")
    void productTombstone() {
        UUID product = UUID.randomUUID();
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        givenMarkdownRule(campaign, product, 1);
        publish(campaign);
        assertThat(outboxRows()).hasSize(1);

        ProductTombstoneService.Outcome outcome = tombstones.apply(product, 5, Instant.now());
        assertThat(outcome.invalidatedRules()).isEqualTo(1);
        assertThat(outcome.watermarkAdvanced()).isTrue();
        Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT invalidation_reason, source_product_revision FROM markdown_rules WHERE product_id = ?", product);
        assertThat(rule).containsEntry("invalidation_reason", "PRODUCT_DELETED").containsEntry("source_product_revision", 5L);

        // Stale duplicate: watermark does not move backwards, still idempotent
        ProductTombstoneService.Outcome stale = tombstones.apply(product, 3, Instant.now());
        assertThat(stale.watermark()).isEqualTo(5);
        assertThat(stale.invalidatedRules()).isZero();

        // Mutation after tombstone is rejected under the same product lock
        CampaignEntity draft = givenActiveWindowCampaign(Duration.ofHours(1));
        assertThatThrownBy(() -> markdownAdmin.create(Long.toString(draft.getId()), etag(draft),
                new MarkdownDtos.CreateMarkdownRuleRequest(product, new DiscountDto.Percentage("PERCENTAGE", 500, null), 0, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.PRODUCT_TOMBSTONED);

        // Pause after tombstone: no INACTIVE emitted back to the deleted product
        transition(campaignAdmin.get(Long.toString(campaign.getId())), PublicationStatus.PAUSED);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    @DisplayName("clone keeps the source immutable and creates new campaign/promotion/markdown identities")
    void cloneCampaign() {
        CampaignEntity source = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(source, DistributionMode.PUBLIC_SAVE, null);
        UUID product = UUID.randomUUID();
        givenMarkdownRule(source, product, 2);
        CampaignEntity published = publish(source);

        CampaignEntity copy = campaignAdmin.clone(Long.toString(published.getId()), etag(published),
                new CampaignDtos.CloneCampaignRequest(uniqueCode("COPY"), "Copy", null,
                        published.getStartTime().plus(Duration.ofDays(1)), published.getEndTime().plus(Duration.ofDays(1)),
                        List.of(new CampaignDtos.PromotionCodeOverride(Long.toString(promotion.getId()), uniqueCode("NEW")))));
        assertThat(copy.getId()).isNotEqualTo(published.getId());
        assertThat(copy.getPublicationStatus()).isEqualTo(PublicationStatus.DRAFT);
        assertThat(copy.getRevision()).isEqualTo(1);
        assertThat(currentRevision(published.getId())).isEqualTo(published.getRevision());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM promotions WHERE campaign_id = ?", Long.class,
                copy.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM markdown_rules WHERE campaign_id = ? AND product_id = ?",
                Long.class, copy.getId(), product)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT claimed_quantity + used_quantity FROM promotion_budgets b JOIN promotions p ON p.id = b.promotion_id WHERE p.campaign_id = ?",
                Long.class, copy.getId())).isZero();
    }
}
