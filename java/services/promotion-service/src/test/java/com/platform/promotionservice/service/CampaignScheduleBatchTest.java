package com.platform.promotionservice.service;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.OutboxEventRepository;
import com.platform.promotionservice.infrastructure.redis.ScheduleIndex;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-021: START/END chia campaign thành các lô rule, mỗi lô một transaction; lô lỗi không chặn lô sau;
 * member chỉ bị ZREM khi mọi lô xong hoặc member lỗi thời. Transaction manager là mock nên đếm được số
 * transaction (một lô = một {@code getTransaction}) mà không cần database.
 */
class CampaignScheduleBatchTest {

    private static final long CAMPAIGN_ID = 42L;
    private static final long REVISION = 3L;
    private static final String RAW_MEMBER = "42:3:START";

    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");

    private CampaignRepository campaigns;
    private MarkdownRuleRepository rules;
    private ScheduleIndex schedule;
    private MarkdownSnapshotService snapshots;
    private PlatformTransactionManager transactions;
    private SimpleMeterRegistry meters;
    private CampaignScheduleService service;
    private List<MarkdownRuleEntity> campaignRules;

    private final Answer<MarkdownSnapshotService.BatchResult> emitAll = invocation ->
            new MarkdownSnapshotService.BatchResult(invocation.<List<UUID>>getArgument(0).size(), 0);

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        rules = mock(MarkdownRuleRepository.class);
        schedule = mock(ScheduleIndex.class);
        snapshots = mock(MarkdownSnapshotService.class);
        transactions = mock(PlatformTransactionManager.class);
        meters = new SimpleMeterRegistry();

        PromotionProperties properties = new PromotionProperties();
        properties.getSchedule().setEmitBatchSize(200);
        service = new CampaignScheduleService(campaigns, rules, mock(OutboxEventRepository.class), schedule, snapshots,
                new TransactionTemplate(transactions), properties, Clock.fixed(now, ZoneOffset.UTC), meters);

        when(schedule.due(anyLong(), anyInt())).thenReturn(List.of(member()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(REVISION)));
        campaignRules = rules(450);
        // pageAfter của repository thật: id > afterId, id tăng dần, tối đa pageSize.
        when(rules.pageAfter(eq(CAMPAIGN_ID), anyLong(), any(Pageable.class))).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(1);
            int size = invocation.<Pageable>getArgument(2).getPageSize();
            return campaignRules.stream().filter(rule -> rule.getId() > afterId).limit(size).toList();
        });
        doAnswer(emitAll).when(snapshots).emitBatch(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("450 rule, lô 200 → 3 transaction (200/200/50), ZREM đúng một lần sau lô cuối")
    void splitsCampaignIntoBatchesWithOneTransactionEach() {
        int processed = service.processDue();

        assertThat(processed).isEqualTo(1);
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(3)).commit(any());
        verify(transactions, never()).rollback(any());
        assertThat(emittedBatchSizes()).containsExactly(200, 200, 50);
        verify(schedule, times(1)).remove(RAW_MEMBER);
        assertThat(meters.counter("promotion.schedule.emit.pages", "outcome", "committed").count()).isEqualTo(3);
        assertThat(meters.counter("promotion.schedule.emit.snapshots", "result", "emitted").count()).isEqualTo(450);
    }

    @Test
    @DisplayName("lô giữa lỗi: chỉ lô đó rollback, các lô còn lại vẫn commit, member KHÔNG bị ZREM")
    void failedBatchDoesNotBlockLaterBatchesAndKeepsMember() {
        doAnswer(emitAll).doThrow(new IllegalStateException("poison product")).doAnswer(emitAll)
                .when(snapshots).emitBatch(any(), any(), anyLong(), any());

        int processed = service.processDue();

        assertThat(processed).isZero();
        verify(snapshots, times(3)).emitBatch(any(), any(), anyLong(), any());
        verify(transactions, times(2)).commit(any());
        verify(transactions, times(1)).rollback(any());
        verify(schedule, never()).remove(any());
        assertThat(meters.counter("promotion.schedule.emit.pages", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("vòng sau chạy lại cả campaign và dọn member khi không còn lô lỗi")
    void retryAfterFailureCompletesAndRemovesMember() {
        doThrow(new IllegalStateException("transient")).doAnswer(emitAll)
                .when(snapshots).emitBatch(any(), any(), anyLong(), any());

        assertThat(service.processDue()).isZero();
        verify(schedule, never()).remove(any());

        // Lần 2: mọi lô thành công (lô 1 trước đó lỗi nên giờ mới phát; dedup pre-check nằm trong emitBatch).
        assertThat(service.processDue()).isEqualTo(1);
        verify(schedule, times(1)).remove(RAW_MEMBER);
    }

    @Test
    @DisplayName("campaign đổi revision giữa các lô → dừng ngay, dọn member lỗi thời")
    void staleCampaignStopsRemainingBatches() {
        // findById: process() → rev 3, lô 1 → rev 3, lô 2 → rev 4 (admin vừa pause/đổi revision).
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(REVISION)), Optional.of(campaign(REVISION)),
                Optional.of(campaign(REVISION + 1)));

        int processed = service.processDue();

        assertThat(processed).isZero();
        verify(snapshots, times(1)).emitBatch(any(), any(), anyLong(), any());
        verify(schedule, times(1)).remove(RAW_MEMBER);
        assertThat(meters.counter("promotion.schedule.emit.pages", "outcome", "stale").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rule đã bị Product tombstone invalidate không được đưa vào lô")
    void invalidatedRulesAreExcludedFromBatches() {
        campaignRules = rules(3);
        campaignRules.get(1).setInvalidatedAt(now);

        service.processDue();

        ArgumentCaptor<List<UUID>> products = productsCaptor();
        verify(snapshots).emitBatch(products.capture(), any(), anyLong(), eq(ScheduleTrigger.START));
        assertThat(products.getValue()).containsExactly(campaignRules.get(0).getProductId(), campaignRules.get(2).getProductId());
    }

    @Test
    @DisplayName("campaign không có rule: không mở transaction nào, vẫn dọn member")
    void campaignWithoutRulesOpensNoTransaction() {
        campaignRules = List.of();

        assertThat(service.processDue()).isEqualTo(1);

        verify(transactions, never()).getTransaction(any());
        verify(schedule, times(1)).remove(RAW_MEMBER);
    }

    @Test
    @DisplayName("member START sớm hơn start_time: dọn member, không phát gì")
    void earlyStartMemberIsDroppedWithoutEmitting() {
        CampaignEntity upcoming = campaign(REVISION);
        upcoming.setStartTime(now.plus(Duration.ofMinutes(5)));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(upcoming));

        assertThat(service.processDue()).isZero();

        verify(snapshots, never()).emitBatch(any(), any(), anyLong(), any());
        verify(schedule, times(1)).remove(RAW_MEMBER);
    }

    // ------------------------------------------------------------------ fixtures

    private List<Integer> emittedBatchSizes() {
        ArgumentCaptor<List<UUID>> products = productsCaptor();
        verify(snapshots, times(3)).emitBatch(products.capture(), any(), anyLong(), any());
        return products.getAllValues().stream().map(List::size).toList();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<UUID>> productsCaptor() {
        return ArgumentCaptor.forClass((Class<List<UUID>>) (Class<?>) List.class);
    }

    private ScheduleIndex.Member member() {
        return new ScheduleIndex.Member(CAMPAIGN_ID, REVISION, ScheduleTrigger.START, RAW_MEMBER);
    }

    private CampaignEntity campaign(long revision) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setRevision(revision);
        campaign.setPublicationStatus(PublicationStatus.PUBLISHED);
        campaign.setStartTime(now.minus(Duration.ofHours(1)));
        campaign.setEndTime(now.plus(Duration.ofHours(1)));
        return campaign;
    }

    private static List<MarkdownRuleEntity> rules(int count) {
        List<MarkdownRuleEntity> list = new ArrayList<>();
        IntStream.rangeClosed(1, count).forEach(i -> {
            MarkdownRuleEntity rule = new MarkdownRuleEntity();
            rule.setId((long) i);
            rule.setCampaignId(CAMPAIGN_ID);
            rule.setProductId(UUID.randomUUID());
            list.add(rule);
        });
        return list;
    }
}
