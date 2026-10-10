package com.platform.promotionservice.service;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.rule.ConditionNode;
import com.platform.promotionservice.domain.rule.RuleFields;
import com.platform.promotionservice.domain.rule.RuleOperator;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DiscoveryBatchTest {
    @Test
    void advancesCursorPastMissingSnapshotsAndUsesConfiguredBatchSizeAndOneTime() {
        var wallets = mock(UserCouponWalletRepository.class);
        var promotions = mock(PromotionRepository.class);
        var snapshots = mock(PromotionSnapshotService.class);
        var runtime = mock(RuntimeStore.class);
        var properties = new PromotionProperties();
        properties.getDiscovery().setBatchSize(2);
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        UUID user = UUID.randomUUID();
        var page = PageRequest.of(0, 2);
        when(wallets.findSavedAfter(user, 0, page)).thenReturn(List.of());
        when(promotions.findPublicActiveAfter(user, now, 0, page)).thenReturn(List.of(1L, 2L));
        when(promotions.findPublicActiveAfter(user, now, 2, page)).thenReturn(List.of(3L, 4L));
        when(promotions.findPublicActiveAfter(user, now, 4, page)).thenReturn(List.of());
        when(snapshots.findAll(List.of(1L, 2L))).thenReturn(Map.of());
        when(snapshots.findAll(List.of(3L, 4L))).thenReturn(Map.of(3L, VoucherEvaluationBatchTest.candidate(3, 0).view()));
        when(snapshots.parseRule("{}")).thenReturn(
                ConditionNode.of(RuleOperator.GREATER_THAN_OR_EQUAL, RuleFields.CART_TOTAL_AMOUNT, 100000));
        when(runtime.readAll(List.of(3L))).thenReturn(Map.of());
        var discovery = new DiscoveryService(wallets, promotions, mock(PromotionJdbc.class), snapshots,
                new VoucherEvaluationService(snapshots, runtime), Clock.fixed(now, ZoneOffset.UTC), properties);
        var context = new CheckoutDtos.CheckoutContext(MoneyDto.vnd(350000), "STRIPE", "CHECKOUT",
                List.of(new CheckoutDtos.CheckoutItem(UUID.randomUUID(), "SKU-1", 1, MoneyDto.vnd(350000), List.of())), Map.of());

        var result = discovery.discover(user, new CheckoutDtos.DiscoveryRequest(context, 1, false));
        assertThat(result.items()).extracting(CheckoutDtos.VoucherEvaluation::promotionId).containsExactly("3");
        assertThat(result.evaluatedAt()).isEqualTo(now);
        verify(promotions).findPublicActiveAfter(user, now, 0, page);
        verify(promotions).findPublicActiveAfter(user, now, 2, page);
        verify(promotions).findPublicActiveAfter(user, now, 4, page);
        verifyNoMoreInteractions(promotions);
    }
}
