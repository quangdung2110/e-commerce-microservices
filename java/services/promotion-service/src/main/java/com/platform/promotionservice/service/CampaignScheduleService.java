package com.platform.promotionservice.service;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.event.MarkdownSnapshotEvents;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.OutboxEventRepository;
import com.platform.promotionservice.infrastructure.redis.ScheduleIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * WF-PROMO-002: campaign START/END qua Redis ZSET + DB fallback. Worker re-read
 * PostgreSQL (ID, revision, status, window) trước khi phát; business/outbox commit
 * trước ZREM; START xử lý sau end_time chỉ phát INACTIVE (không phát ACTIVE muộn).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignScheduleService {

    private final CampaignRepository campaigns;
    private final MarkdownRuleRepository rules;
    private final OutboxEventRepository outbox;
    private final ScheduleIndex schedule;
    private final MarkdownSnapshotService snapshots;
    private final TransactionTemplate transactionTemplate;
    private final PromotionProperties properties;
    private final Clock clock;

    /** Sau publish commit: ZADD START (nếu chưa tới) và END. */
    public void scheduleBoundaries(CampaignEntity campaign, Instant now) {
        if (campaign.getPublicationStatus() != PublicationStatus.PUBLISHED) {
            return;
        }
        if (now.isBefore(campaign.getStartTime())) {
            schedule.add(campaign.getId(), campaign.getRevision(), ScheduleTrigger.START,
                    campaign.getStartTime().toEpochMilli());
        }
        schedule.add(campaign.getId(), campaign.getRevision(), ScheduleTrigger.END, campaign.getEndTime().toEpochMilli());
    }

    /** Một vòng loop: lấy due member, re-read DB, phát snapshot trong transaction, rồi ZREM. */
    public int processDue() {
        long nowMs = clock.millis();
        int processed = 0;
        for (ScheduleIndex.Member member : schedule.due(nowMs, properties.getSchedule().getBatchSize())) {
            try {
                Boolean emitted = transactionTemplate.execute(status -> process(member));
                schedule.remove(member.raw());
                if (Boolean.TRUE.equals(emitted)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.warn("Schedule member {} failed, will retry: {}", member.raw(), e.getMessage());
            }
        }
        return processed;
    }

    private boolean process(ScheduleIndex.Member member) {
        Optional<CampaignEntity> found = campaigns.findById(member.campaignId());
        if (found.isEmpty()) {
            return false;
        }
        CampaignEntity campaign = found.get();
        if (campaign.getRevision() != member.revision()
                || campaign.getPublicationStatus() != PublicationStatus.PUBLISHED) {
            log.info("Stale schedule member {} (revision={}, status={}): no-op", member.raw(),
                    campaign.getRevision(), campaign.getPublicationStatus());
            return false;
        }
        Instant now = clock.instant();
        if (member.trigger() == ScheduleTrigger.START && now.isBefore(campaign.getStartTime())) {
            return false;
        }
        if (member.trigger() == ScheduleTrigger.END && now.isBefore(campaign.getEndTime())) {
            return false;
        }
        emitForCampaign(campaign, campaign.getRevision(), member.trigger());
        return true;
    }

    /** Phát snapshot cho mọi product có rule chưa invalidated của campaign; dedup theo cause. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void emitForCampaign(CampaignEntity campaign, long causeRevision, ScheduleTrigger trigger) {
        for (MarkdownRuleEntity rule : rules.findByCampaignIdOrderByIdAsc(campaign.getId())) {
            if (rule.isInvalidated()) {
                continue;
            }
            snapshots.emit(rule.getProductId(), campaign, causeRevision, trigger);
        }
    }

    /**
     * DB fallback / startup rebuild: đảm bảo ZSET có member cho mọi campaign PUBLISHED còn
     * boundary trong lookback. Boundary đã qua mà outbox chưa có causal row được ZADD với
     * score cũ để worker xử lý ngay; dedup key chặn phát trùng.
     */
    @Transactional(readOnly = true)
    public int rebuildSchedule() {
        Instant now = clock.instant();
        Instant since = now.minus(properties.getSchedule().getFallbackLookback());
        int added = 0;
        for (CampaignEntity campaign : campaigns.publishedEndingAfter(since)) {
            List<MarkdownRuleEntity> campaignRules = rules.findByCampaignIdOrderByIdAsc(campaign.getId());
            boolean hasStart = schedule.contains(campaign.getId(), campaign.getRevision(), ScheduleTrigger.START);
            boolean startDue = !campaign.getStartTime().isAfter(now);
            boolean needStart = !hasStart && (startDue ? needsEmission(campaign, campaignRules, now) : true);
            if (needStart) {
                schedule.add(campaign.getId(), campaign.getRevision(), ScheduleTrigger.START,
                        campaign.getStartTime().toEpochMilli());
                added++;
            }
            if (!schedule.contains(campaign.getId(), campaign.getRevision(), ScheduleTrigger.END)
                    && (now.isBefore(campaign.getEndTime()) || missingCause(campaign, campaignRules, ScheduleTrigger.END))) {
                schedule.add(campaign.getId(), campaign.getRevision(), ScheduleTrigger.END,
                        campaign.getEndTime().toEpochMilli());
                added++;
            }
        }
        return added;
    }

    /** In-window START còn thiếu nếu chưa có row START lẫn PUBLISH cho một product nào đó. */
    private boolean needsEmission(CampaignEntity campaign, List<MarkdownRuleEntity> campaignRules, Instant now) {
        if (!now.isBefore(campaign.getEndTime())) {
            return false;
        }
        for (MarkdownRuleEntity rule : campaignRules) {
            if (rule.isInvalidated()) {
                continue;
            }
            boolean started = outbox.findByDedupKey(MarkdownSnapshotEvents.dedupKey(rule.getProductId(),
                    campaign.getId(), campaign.getRevision(), ScheduleTrigger.START)).isPresent();
            boolean published = outbox.findByDedupKey(MarkdownSnapshotEvents.dedupKey(rule.getProductId(),
                    campaign.getId(), campaign.getRevision(), ScheduleTrigger.PUBLISH)).isPresent();
            if (!started && !published) {
                return true;
            }
        }
        return false;
    }

    private boolean missingCause(CampaignEntity campaign, List<MarkdownRuleEntity> campaignRules, ScheduleTrigger trigger) {
        for (MarkdownRuleEntity rule : campaignRules) {
            if (rule.isInvalidated()) {
                continue;
            }
            if (outbox.findByDedupKey(MarkdownSnapshotEvents.dedupKey(rule.getProductId(), campaign.getId(),
                    campaign.getRevision(), trigger)).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
