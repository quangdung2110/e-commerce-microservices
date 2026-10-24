package com.platform.promotionservice.integration;

import com.platform.promotionservice.config.VoucherReadWarmup;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherWarmupIntegrationTest extends AbstractPromotionIntegrationTest {
    @Autowired private DiscoveryService discovery;
    @Autowired private VoucherReadWarmup warmup;

    @Test
    void emptyCatalogPrimesAstAndDtoWithoutWritingBusinessOrRedisState() throws Exception {
        var response = discovery.warmupOptions(request());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).evaluation().eligible()).isTrue();
        assertThat(response.items().get(0).evaluation().discount()).isEqualTo(MoneyDto.vnd(35000));
        assertThat(objectMapper.readValue(objectMapper.writeValueAsBytes(response),
                CheckoutDtos.VoucherOptionList.class)).isEqualTo(response);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM promotions", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_coupon_wallets", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM promotion_outbox_events", Long.class)).isZero();
        assertThat(redis.keys("*")).isEmpty();
        assertThat(warmup.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void readsActiveCampaignsWithoutClaimingSavingReservingOrEmittingEvents() throws Exception {
        var campaign = givenActiveWindowCampaign(Duration.ofHours(1));
        var promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        long events = jdbcTemplate.queryForObject("SELECT count(*) FROM promotion_outbox_events", Long.class);
        var beforeBudget = jdbcTemplate.queryForMap("SELECT * FROM promotion_budgets WHERE promotion_id = ?", promotion.getId());
        var response = discovery.warmupOptions(request());
        assertThat(response.items()).extracting(item -> item.evaluation().promotionId())
                .contains(Long.toString(promotion.getId()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_coupon_wallets", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM coupon_claims", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM promotion_outbox_events", Long.class)).isEqualTo(events);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM promotion_budgets WHERE promotion_id = ?", promotion.getId()))
                .isEqualTo(beforeBudget);
    }

    private CheckoutDtos.VoucherOptionRequest request() {
        return new CheckoutDtos.VoucherOptionRequest(UUID.randomUUID(), null, context(350000), true, true, 50);
    }
}
