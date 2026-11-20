package com.platform.promotionservice.integration;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.service.ClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-PROMO-001: pause/cancel đóng claim gate TRƯỚC DB; không đóng được thì lệnh thất bại (503) và không đổi gì,
 * để admin không tưởng claim đã dừng trong khi Redis vẫn cấp suất.
 */
class PauseGateCloseFailureIntegrationTest extends AbstractPromotionIntegrationTest {

    @MockitoSpyBean
    RuntimeStore runtime;
    @Autowired
    ClaimService claims;

    @AfterEach
    void resetSpy() {
        Mockito.reset(runtime);
    }

    @Test
    @DisplayName("gate cannot be closed: pause fails with 503, campaign stays PUBLISHED, claims keep working")
    void pauseFailsWhenGateCannotBeClosed() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, 2L);
        CampaignEntity published = publish(campaign);
        long revision = published.getRevision();

        Mockito.doThrow(new RedisConnectionFailureException("redis down")).when(runtime).closeClaimGate(promotion.getId());

        assertThatThrownBy(() -> transition(published, PublicationStatus.PAUSED))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.PROMOTION_UNAVAILABLE);
        assertThatThrownBy(() -> transition(published, PublicationStatus.CANCELLED))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.PROMOTION_UNAVAILABLE);

        assertThat(jdbcTemplate.queryForObject("SELECT publication_status FROM campaigns WHERE id = ?", String.class,
                campaign.getId())).isEqualTo("PUBLISHED");
        assertThat(currentRevision(campaign.getId())).isEqualTo(revision);
        assertThat(outboxRows()).isEmpty();
        RuntimeStore.RuntimeState gate = runtime.read(promotion.getId()).orElseThrow();
        assertThat(gate.claimEnabled()).isTrue();
        assertThat(gate.claimRevision()).isEqualTo(revision);
        assertThat(claims.claim(UUID.randomUUID(), promotion.getId(), revision)).isInstanceOf(ClaimService.Committed.class);
    }

    @Test
    @DisplayName("second gate fails after the first was closed: whole command aborts and the first gate reopens")
    void partiallyClosedGatesReopen() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity first = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, 2L);
        PromotionEntity second = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, 2L);
        CampaignEntity published = publish(campaign);
        long revision = published.getRevision();

        Mockito.doThrow(new RedisConnectionFailureException("redis down")).when(runtime).closeClaimGate(second.getId());

        assertThatThrownBy(() -> transition(published, PublicationStatus.PAUSED))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(ErrorCode.PROMOTION_UNAVAILABLE);

        assertThat(jdbcTemplate.queryForObject("SELECT publication_status FROM campaigns WHERE id = ?", String.class,
                campaign.getId())).isEqualTo("PUBLISHED");
        // The first gate was really closed by the real method, then reopened by the rollback hook.
        Mockito.verify(runtime).closeClaimGate(first.getId());
        assertThat(runtime.read(first.getId()).orElseThrow().claimEnabled()).isTrue();
        assertThat(runtime.read(second.getId()).orElseThrow().claimEnabled()).isTrue();
        assertThat(claims.claim(UUID.randomUUID(), first.getId(), revision)).isInstanceOf(ClaimService.Committed.class);
    }
}
