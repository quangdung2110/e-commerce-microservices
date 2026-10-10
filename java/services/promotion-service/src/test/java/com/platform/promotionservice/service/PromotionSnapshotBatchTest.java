package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.SnapshotCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PromotionSnapshotBatchTest {
    private final SnapshotCache cache = mock(SnapshotCache.class);
    private final PromotionRepository promotions = mock(PromotionRepository.class);
    private final PromotionSnapshotService snapshots = new PromotionSnapshotService(cache, promotions,
            mock(CampaignRepository.class), new ObjectMapper().findAndRegisterModules());

    @Test
    void unavailableRedisUsesOneProjectionQueryForAllUniqueIds() {
        when(cache.currentVersion()).thenReturn(-1L);
        PromotionView view = VoucherEvaluationBatchTest.candidate(1, 0).view();
        when(promotions.findSnapshotViews(List.of(1L, 2L), 0)).thenReturn(List.of(view));

        assertThat(snapshots.findAll(List.of(1L, 2L, 1L))).isEqualTo(Map.of(1L, view));
        verify(cache).currentVersion();
        verifyNoMoreInteractions(cache);
        verify(promotions).findSnapshotViews(List.of(1L, 2L), 0);
        verifyNoMoreInteractions(promotions);
    }

    @Test
    void emptyBatchDoesNotTouchDependencies() {
        assertThat(snapshots.findAll(List.of())).isEmpty();
        verifyNoInteractions(cache, promotions);
    }
}
