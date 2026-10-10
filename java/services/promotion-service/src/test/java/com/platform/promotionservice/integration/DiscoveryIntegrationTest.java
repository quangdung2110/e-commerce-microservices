package com.platform.promotionservice.integration;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.PromotionRedisKeys;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.service.PromotionSnapshotService;
import com.platform.promotionservice.service.PromotionView;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests with real SQL projections, Redis pipelines and more than two public batches. */
class DiscoveryIntegrationTest extends AbstractPromotionIntegrationTest {
    @Autowired DiscoveryService discovery;
    @Autowired PromotionRepository promotions;
    @Autowired PromotionSnapshotService snapshots;
    @Autowired RuntimeStore runtime;

    @Test
    void ranksAcrossAllBatchesEvenAfterFindingEnoughEligibleVouchers() {
        seedPublic(401);
        jdbcTemplate.update("UPDATE promotions SET discount_value = 1200 WHERE id = 201");
        jdbcTemplate.update("UPDATE promotions SET discount_value = 2000 WHERE id = 401");
        UUID user = UUID.randomUUID();

        var result = discovery.discover(user, new CheckoutDtos.DiscoveryRequest(context(350000), 2, false));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId)
                .containsExactly("401", "201");
        assertThat(result.items()).extracting(item -> item.discount().amountMinor()).containsExactly(50000L, 42000L);
        assertThat(result.items()).allMatch(item -> item.source() == VoucherSource.PUBLIC && item.eligible());

        // Re-run through Checkout options: warm cache pipeline must preserve evaluation and display.
        var options = discovery.options(new CheckoutDtos.VoucherOptionRequest(user, null, context(350000), true, false, 2));
        assertThat(options.items()).extracting(item -> item.evaluation().promotionId()).containsExactly("401", "201");
        assertThat(options.items().get(0).display().code()).isEqualTo("DISC-401");
        assertThat(options.items().get(0).display().name()).isEqualTo("Ten percent");
        assertThat(options.items().get(0).evaluation()).isEqualTo(result.items().get(0));
    }

    @Test
    void continuesPastEntirelyIneligibleBatchesAndRespectsIncludeIneligible() {
        seedPublic(401);
        jdbcTemplate.update("UPDATE promotions SET min_order_amount_vnd = 1000000 WHERE id <= 400");
        UUID user = UUID.randomUUID();
        var result = discovery.discover(user, new CheckoutDtos.DiscoveryRequest(context(350000), 3, false));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("401");

        var all = discovery.discover(user, new CheckoutDtos.DiscoveryRequest(context(350000), 3, true));
        assertThat(all.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("401", "1", "2");
        assertThat(all.items().get(1).reason()).isEqualTo(EligibilityReason.MIN_ORDER_NOT_MET);
        assertThat(all.items().get(1).budgetGuard()).isEqualTo(BudgetGuard.NOT_CHECKED);
    }

    @Test
    void excludesEveryOwnershipStatusBeforePagingAndRanksWalletWithPublic() {
        seedPublic(401);
        UUID user = UUID.randomUUID();
        // 1..198 EXPIRED, 199 USED, 200 SAVED; rows owned by another user do not exclude public results.
        jdbcTemplate.update("""
                INSERT INTO user_coupon_wallets(user_id, promotion_id, status, saved_at, expired_at)
                SELECT ?, id, 'EXPIRED', now() - interval '2 hours', now() - interval '1 hour'
                FROM promotions WHERE id <= 198
                """, user);
        jdbcTemplate.update("""
                INSERT INTO user_coupon_wallets(user_id, promotion_id, status, saved_at, expired_at,
                                               used_order_id, discount_amount_vnd, used_at)
                VALUES (?, 199, 'USED', now(), now() + interval '1 hour', 123, 35000, now())
                """, user);
        saveRows(user, 200, 200);
        saveRows(UUID.randomUUID(), 201, 201);
        jdbcTemplate.update("UPDATE promotions SET discount_value = 2000 WHERE id IN (200, 201)");

        assertThat(promotions.findPublicActiveAfter(user, Instant.now(), 0, PageRequest.of(0, 200)))
                .hasSize(200).startsWith(201L).endsWith(400L);
        var result = discovery.discover(user, new CheckoutDtos.DiscoveryRequest(context(350000), 3, false));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("200", "201", "202");
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::source)
                .containsExactly(VoucherSource.WALLET, VoucherSource.PUBLIC, VoucherSource.PUBLIC);
        assertThat(result.items().get(0).walletId()).isNotNull();

        var walletOnly = discovery.options(new CheckoutDtos.VoucherOptionRequest(user, null, context(350000), false, false, 50));
        assertThat(walletOnly.items()).extracting(item -> item.evaluation().promotionId()).containsExactly("200");
    }

    @Test
    void walletTrayAlsoScansBeyondFirstBatchAndNormalizesExpiry() {
        seedPublic(202);
        UUID user = UUID.randomUUID();
        saveRows(user, 1, 202);
        jdbcTemplate.update("UPDATE promotions SET discount_value = 2000 WHERE id = 202");
        jdbcTemplate.update("UPDATE user_coupon_wallets SET expired_at = now() - interval '1 hour' WHERE promotion_id = 1");
        var result = discovery.suggest(user, new CheckoutDtos.SuggestionRequest(context(350000), 2));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("202", "2");
        assertThat(result.items()).allMatch(item -> item.source() == VoucherSource.WALLET);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM user_coupon_wallets WHERE promotion_id = 1", String.class))
                .isEqualTo("EXPIRED");
    }

    @Test
    void snapshotBatchRepairsCorruptCacheAndVersionMismatch() {
        seedPublic(3);
        Map<Long, PromotionView> cold = snapshots.findAll(List.of(1L, 2L, 3L, 999L));
        assertThat(cold).containsOnlyKeys(1L, 2L, 3L);
        assertThat(redis.getExpire(PromotionRedisKeys.snapshot(1))).isBetween(1L, 3600L);
        assertThat(snapshots.findAll(List.of(1L, 2L, 3L))).isEqualTo(cold);
        redis.opsForValue().set(PromotionRedisKeys.snapshot(2), "invalid-json");
        assertThat(snapshots.findAll(List.of(1L, 2L, 3L))).isEqualTo(cold);
        redis.opsForValue().increment(PromotionRedisKeys.RULES_VERSION);
        jdbcTemplate.update("UPDATE promotions SET name = 'Updated name' WHERE id = 3");
        var refreshed = snapshots.findAll(List.of(1L, 2L, 3L));
        assertThat(refreshed.values()).allMatch(view -> view.cacheVersion() == cold.get(1L).cacheVersion() + 1);
        assertThat(refreshed.get(3L).name()).isEqualTo("Updated name");
    }

    @Test
    void pipelineBudgetGuardsPreserveExhaustedAndMissingRuntimeSemantics() {
        seedPublic(3);
        runtime.writeBudgetProjection(2, 1L, null, 1, 0, 0L, 0L);
        runtime.writeBudgetProjection(3, null, 10000L, 0, 0, 0L, 0L);
        var result = discovery.discover(UUID.randomUUID(), new CheckoutDtos.DiscoveryRequest(context(350000), 3, true));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("1", "2", "3");
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::budgetGuard)
                .containsExactly(BudgetGuard.AVAILABLE, BudgetGuard.EXHAUSTED, BudgetGuard.EXHAUSTED);
        assertThat(result.items().get(1).reason()).isEqualTo(EligibilityReason.BUDGET_EXHAUSTED);
        assertThat(result.items().get(1).discount().amountMinor()).isZero();
        redis.delete(PromotionRedisKeys.runtime(3));
        var afterDelete = discovery.discover(UUID.randomUUID(), new CheckoutDtos.DiscoveryRequest(context(350000), 3, false));
        assertThat(afterDelete.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("1", "3");
        assertThat(afterDelete.items().get(1).budgetGuard()).isEqualTo(BudgetGuard.NOT_CHECKED);
    }

    @Test
    void emptyDiscoveryReturnsEmptyList() {
        assertThat(discovery.discover(UUID.randomUUID(), new CheckoutDtos.DiscoveryRequest(context(350000), null, null)).items())
                .isEmpty();
    }

    private void seedPublic(int count) {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity template = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        if (count > 1) {
            // Bulk fixtures avoid hundreds of admin transactions while exercising the production read path.
            jdbcTemplate.update("""
                    INSERT INTO promotions(campaign_id, code, name, distribution_mode, discount_type, discount_value,
                                           max_discount_vnd, min_order_amount_vnd, conditions_rule, rule_schema_version,
                                           validity_mode, is_enabled)
                    SELECT p.campaign_id, 'DISC-' || n, p.name, p.distribution_mode, p.discount_type, p.discount_value,
                           p.max_discount_vnd, p.min_order_amount_vnd, p.conditions_rule, p.rule_schema_version,
                           p.validity_mode, p.is_enabled
                    FROM promotions p CROSS JOIN generate_series(2, ?) n WHERE p.id = ? ORDER BY n
                    """, count, template.getId());
        }
    }

    private void saveRows(UUID user, int first, int last) {
        jdbcTemplate.update("""
                INSERT INTO user_coupon_wallets(user_id, promotion_id, status, saved_at, expired_at)
                SELECT ?, id, 'SAVED', now(), now() + interval '1 hour'
                FROM promotions WHERE id BETWEEN ? AND ? ORDER BY id
                """, user, first, last);
    }
}
