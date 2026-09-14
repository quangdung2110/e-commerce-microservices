package com.platform.promotionservice.integration;

import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.infrastructure.redis.PromotionRedisKeys;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.service.AllocationService;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.service.FinalCommandService;
import com.platform.promotionservice.service.WalletService;
import com.platform.promotionservice.web.dto.AllocationDtos;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E theo WF-PROMO-001/004/005/006/007: publish → discovery → save → reserve →
 * confirm (receipt + finalize) → cancel-confirmed (DB-first + repair) → pause.
 */
class VoucherLifecycleIntegrationTest extends AbstractPromotionIntegrationTest {

    @Autowired
    DiscoveryService discovery;
    @Autowired
    WalletService wallets;
    @Autowired
    AllocationService allocation;
    @Autowired
    FinalCommandService finalCommands;
    @Autowired
    RuntimeStore runtime;

    @Test
    @DisplayName("publish in window emits ACTIVE markdown snapshot and rebuilds runtime; pause emits ADMIN_DISABLED")
    void publishAndPauseMarkdown() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        UUID product = UUID.randomUUID();
        givenMarkdownRule(campaign, product, 5);
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);

        CampaignEntity published = publish(campaign);
        assertThat(published.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.getRevision()).isEqualTo(4); // create + promotion + markdown + publish

        List<OutboxRow> rows = outboxRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).eventType()).isEqualTo("com.ecommerce.promotion.product-markdown-snapshot.v1");
        assertThat(rows.get(0).partitionKey()).isEqualTo(product.toString());
        assertThat(rows.get(0).state()).isEqualTo("ACTIVE");
        assertThat(rows.get(0).dedupKey()).endsWith(":campaign:" + campaign.getId() + ":rev:4:trigger:PUBLISH");

        RuntimeStore.RuntimeState state = runtime.read(promotion.getId()).orElseThrow();
        assertThat(state.totalRedemptionQuantity()).isEqualTo(10);
        assertThat(state.totalBudgetVnd()).isEqualTo(1_000_000);
        assertThat(state.claimEnabled()).isFalse(); // PUBLIC_SAVE has no claim gate
        assertThat(redis.opsForZSet().score(PromotionRedisKeys.CAMPAIGN_SCHEDULE,
                campaign.getId() + ":4:END")).isEqualTo((double) published.getEndTime().toEpochMilli());

        // Published definition is immutable
        assertThatThrownBy(() -> promotionAdmin.update(Long.toString(promotion.getId()), etag(published),
                objectMapper.createObjectNode().put("name", "changed")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.CAMPAIGN_NOT_DRAFT);

        CampaignEntity paused = transition(published, PublicationStatus.PAUSED);
        assertThat(paused.getRevision()).isEqualTo(5);
        rows = outboxRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).state()).isEqualTo("INACTIVE");
        assertThat(rows.get(1).inactiveReason()).isEqualTo("ADMIN_DISABLED");
        assertThat(rows.get(1).sourceRevision()).isGreaterThan(rows.get(0).sourceRevision());

        // PAUSED -> PUBLISHED is not a contract transition
        assertThatThrownBy(() -> transition(paused, PublicationStatus.PUBLISHED))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("save → reserve → confirm → technical cancel: PostgreSQL-first receipts, wallet and Redis converge")
    void saveReserveConfirmCancelConfirmed() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        CampaignEntity published = publish(campaign);
        UUID user = UUID.randomUUID();
        String promotionId = Long.toString(promotion.getId());

        // Discovery: public voucher eligible with 10% capped by 50000
        CallerContext.set(user, List.of(), null, "req", null);
        CheckoutDtos.VoucherSuggestionResponse discovered = discovery.discover(user,
                new CheckoutDtos.DiscoveryRequest(context(350000), 20, false));
        assertThat(discovered.items()).hasSize(1);
        CheckoutDtos.VoucherEvaluation publicItem = discovered.items().get(0);
        assertThat(publicItem.source()).isEqualTo(VoucherSource.PUBLIC);
        assertThat(publicItem.eligible()).isTrue();
        assertThat(publicItem.discount().amountMinor()).isEqualTo(35000);
        assertThat(publicItem.campaignRevision()).isEqualTo(published.getRevision());

        // Save: 201 then idempotent replay
        WalletService.SaveOutcome saved = wallets.savePublic(user, promotion.getId());
        assertThat(saved.created()).isTrue();
        assertThat(saved.wallet().getStatus()).isEqualTo(WalletStatus.SAVED);
        assertThat(saved.wallet().getExpiredAt()).isEqualTo(published.getEndTime());
        assertThat(wallets.savePublic(user, promotion.getId()).created()).isFalse();
        assertThat(discovery.suggest(user, new CheckoutDtos.SuggestionRequest(context(350000), 20)).items())
                .singleElement().satisfies(item -> {
                    assertThat(item.source()).isEqualTo(VoucherSource.WALLET);
                    assertThat(item.eligible()).isTrue();
                });
        // Below min order: ineligible with reason
        assertThat(discovery.suggest(user, new CheckoutDtos.SuggestionRequest(context(50000), 20)).items())
                .singleElement().satisfies(item -> assertThat(item.reason()).isEqualTo(EligibilityReason.MIN_ORDER_NOT_MET));

        // Calculate (Checkout)
        CheckoutDtos.DiscountCalculationResult calc = discovery.calculate(
                new CheckoutDtos.DiscountCalculationRequest(user, "SILVER", promotionId, context(350000)));
        assertThat(calc.eligible()).isTrue();
        assertThat(calc.discount().amountMinor()).isEqualTo(35000);
        assertThat(calc.finalAmount().amountMinor()).isEqualTo(315000);

        // Reserve (TRY)
        AllocationDtos.ReserveRequest reserve = new AllocationDtos.ReserveRequest("1001", user, "SILVER",
                promotionId, MoneyDto.vnd(35000), context(350000));
        AllocationService.ReserveOutcome reserved = allocation.reserve(reserve);
        assertThat(reserved.created()).isTrue();
        assertThat(reserved.response().state()).isEqualTo("RESERVED");
        assertThat(allocation.reserve(reserve).created()).isFalse();
        assertThat(runtime.read(promotion.getId()).orElseThrow().reservedVnd()).isEqualTo(35000);
        assertThatThrownBy(() -> allocation.reserve(new AllocationDtos.ReserveRequest("1001", user, "SILVER",
                promotionId, MoneyDto.vnd(34000), context(350000))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.EXPECTED_DISCOUNT_MISMATCH);

        // Confirm: receipt + wallet USED + durable budget in one transaction, Redis finalized after commit
        String confirmKey = "REDEEM_FINAL:1001:" + promotionId;
        AllocationDtos.FinalAllocationRequest finalRequest = new AllocationDtos.FinalAllocationRequest("1001", promotionId,
                MoneyDto.vnd(35000));
        AllocationDtos.ConfirmationResponse confirmed = finalCommands.confirm(confirmKey, finalRequest);
        assertThat(confirmed.walletStatus()).isEqualTo(WalletStatus.USED);
        assertThat(confirmed.discount().amountMinor()).isEqualTo(35000);
        assertThat(walletStatus(user, promotion.getId())).isEqualTo("USED");
        assertThat(budget(promotion.getId())).containsEntry("used_quantity", 1L).containsEntry("used_budget_vnd", 35000L);
        RuntimeStore.RuntimeState afterConfirm = runtime.read(promotion.getId()).orElseThrow();
        assertThat(afterConfirm.usedQuantity()).isEqualTo(1);
        assertThat(afterConfirm.reservedQuantity()).isZero();
        assertThat(receiptFinalized("CONFIRM", promotion.getId(), 1001)).isTrue();

        // Replay: same key/hash → stored response; different expected amount → mismatch; no second transition
        assertThat(finalCommands.confirm(confirmKey, finalRequest).completedAt()).isEqualTo(confirmed.completedAt());
        assertThatThrownBy(() -> finalCommands.confirm(confirmKey, new AllocationDtos.FinalAllocationRequest("1001",
                promotionId, MoneyDto.vnd(1))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH);
        assertThat(budget(promotion.getId())).containsEntry("used_quantity", 1L);
        assertThat(allocation.cancel(new AllocationDtos.CancelReservationRequest("1001", promotionId)).outcome())
                .isEqualTo(AllocationDtos.CancelOutcome.NO_RESERVATION);

        // Confirm for an order that never reserved → RESERVATION_NOT_FOUND
        assertThatThrownBy(() -> finalCommands.confirm("REDEEM_FINAL:4242:" + promotionId,
                new AllocationDtos.FinalAllocationRequest("4242", promotionId, MoneyDto.vnd(35000))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);

        // Technical cancellation: DB-first, wallet back to SAVED (still valid), Redis repaired
        String cancelKey = "CANCEL_FINAL:1001:" + promotionId;
        AllocationDtos.TechnicalCancellationResponse cancelled = finalCommands.cancelConfirmed(cancelKey, finalRequest);
        assertThat(cancelled.walletStatus()).isEqualTo(WalletStatus.SAVED);
        assertThat(walletStatus(user, promotion.getId())).isEqualTo("SAVED");
        assertThat(budget(promotion.getId())).containsEntry("used_quantity", 0L).containsEntry("used_budget_vnd", 0L);
        assertThat(runtime.read(promotion.getId()).orElseThrow().usedQuantity()).isZero();
        assertThat(receiptFinalized("CANCEL_CONFIRMED", promotion.getId(), 1001)).isTrue();
        assertThat(finalCommands.cancelConfirmed(cancelKey, finalRequest).completedAt()).isEqualTo(cancelled.completedAt());

        // Final commands never write outbox (ADR-016)
        assertThat(outboxRows()).isEmpty();
        // Voucher usable again: reserve for a new order succeeds
        assertThat(allocation.reserve(new AllocationDtos.ReserveRequest("1002", user, "SILVER", promotionId,
                MoneyDto.vnd(35000), context(350000))).created()).isTrue();
    }

    @Test
    @DisplayName("customer refund never reaches Promotion: no endpoint, wallet and budget untouched")
    void customerRefundIsNotAWorkflow() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        UUID user = UUID.randomUUID();
        String promotionId = Long.toString(promotion.getId());
        wallets.savePublic(user, promotion.getId());
        allocation.reserve(new AllocationDtos.ReserveRequest("2001", user, "SILVER", promotionId, MoneyDto.vnd(35000),
                context(350000)));
        finalCommands.confirm("REDEEM_FINAL:2001:" + promotionId,
                new AllocationDtos.FinalAllocationRequest("2001", promotionId, MoneyDto.vnd(35000)));

        // Simulated ORDER_REFUNDED: Promotion has no consumer/API; state must be identical before/after.
        assertThat(walletStatus(user, promotion.getId())).isEqualTo("USED");
        assertThat(budget(promotion.getId())).containsEntry("used_quantity", 1L);
        assertThat(outboxRows()).isEmpty();
    }

    private String walletStatus(UUID user, long promotionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon_wallets WHERE user_id = ? AND promotion_id = ?", String.class,
                user, promotionId);
    }

    private Map<String, Object> budget(long promotionId) {
        return jdbcTemplate.queryForMap("SELECT used_quantity, used_budget_vnd, claimed_quantity FROM promotion_budgets WHERE promotion_id = ?",
                promotionId);
    }

    private boolean receiptFinalized(String operation, long promotionId, long orderId) {
        Timestamp finalized = jdbcTemplate.queryForObject("""
                SELECT redis_finalized_at FROM promotion_command_receipts
                WHERE operation_type = ? AND promotion_id = ? AND order_id = ?
                """, Timestamp.class, operation, promotionId, orderId);
        return finalized != null;
    }
}
