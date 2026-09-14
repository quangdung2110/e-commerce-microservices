package com.platform.promotionservice.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.entity.PromotionOutboxEventEntity;
import com.platform.promotionservice.domain.enums.InactiveReason;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.event.MarkdownSnapshotEvents;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.CatalogProductTombstoneRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF-PROMO-007: current-state markdown snapshot cho một product, trong transaction có
 * advisory lock theo product; cấp {@code source_revision} monotonic; insert outbox bằng
 * causal dedup key (product + campaign + revision + trigger, không chứa state).
 * Không phát cho product đã có terminal tombstone watermark.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkdownSnapshotService {

    private final MarkdownRuleRepository rules;
    private final CampaignRepository campaigns;
    private final CatalogProductTombstoneRepository tombstones;
    private final PromotionJdbc jdbc;
    private final MarkdownSnapshotEvents events;

    /**
     * Tính và ghi snapshot hiện hành của product do cause (campaign, revision, trigger).
     * Chạy trong transaction hiện tại (admin publish/pause/cancel) hoặc tự mở (scheduler).
     * Trả true nếu insert mới, false nếu dedup hoặc bị skip vì tombstone.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean emit(UUID productId, CampaignEntity cause, long causeRevision, ScheduleTrigger trigger) {
        jdbc.lockProduct(productId);
        if (tombstones.existsById(productId)) {
            log.debug("Skip markdown snapshot for tombstoned product {}", productId);
            return false;
        }
        String dedupKey = MarkdownSnapshotEvents.dedupKey(productId, cause.getId(), causeRevision, trigger);
        Instant now = jdbc.dbNow();
        MarkdownSnapshotEvents.Snapshot snapshot = currentState(productId, cause, trigger, now);
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        String payload = events.toCloudEventJson(eventId, snapshot, now);
        int inserted = jdbc.insertOutboxIfAbsent(eventId, MarkdownSnapshotEvents.EVENT_TYPE,
                PromotionOutboxEventEntity.AGGREGATE_PRODUCT, productId.toString(), productId.toString(),
                snapshot.sourceRevision(), dedupKey, payload, now);
        if (inserted == 0) {
            log.debug("Markdown snapshot dedup hit for {}", dedupKey);
            return false;
        }
        log.info("Markdown snapshot product={} state={} source_revision={} trigger={} campaign={}/{}",
                productId, snapshot.state(), snapshot.sourceRevision(), trigger, cause.getId(), causeRevision);
        return true;
    }

    /** Winner = rule enabled, chưa invalidated, campaign ACTIVE; priority desc rồi id asc. */
    private MarkdownSnapshotEvents.Snapshot currentState(UUID productId, CampaignEntity cause,
                                                          ScheduleTrigger trigger, Instant now) {
        List<MarkdownRuleEntity> candidates = rules.findWinnerCandidates(productId, now);
        long sourceRevision = jdbc.nextSourceRevision();
        if (candidates.isEmpty()) {
            return MarkdownSnapshotEvents.Snapshot.inactive(productId, sourceRevision, inactiveReason(trigger, cause, now));
        }
        MarkdownRuleEntity winner = candidates.get(0);
        CampaignEntity campaign = winner.getCampaignId().equals(cause.getId()) ? cause
                : campaigns.findById(winner.getCampaignId()).orElseThrow();
        return MarkdownSnapshotEvents.Snapshot.active(productId, sourceRevision, winner,
                campaign.getStartTime(), campaign.getEndTime());
    }

    /**
     * END, hoặc START xử lý khi cause đã qua end_time → EXPIRED; PAUSE/CANCEL →
     * ADMIN_DISABLED; PUBLISH/START không có winner (rule disabled/invalidated) → NO_ACTIVE_RULE.
     */
    private static InactiveReason inactiveReason(ScheduleTrigger trigger, CampaignEntity cause, Instant now) {
        return switch (trigger) {
            case END -> InactiveReason.EXPIRED;
            case PAUSE, CANCEL -> InactiveReason.ADMIN_DISABLED;
            case START, PUBLISH -> !now.isBefore(cause.getEndTime()) ? InactiveReason.EXPIRED
                    : InactiveReason.NO_ACTIVE_RULE;
        };
    }
}
