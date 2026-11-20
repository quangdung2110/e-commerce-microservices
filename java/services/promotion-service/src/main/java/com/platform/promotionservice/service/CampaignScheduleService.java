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
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WF-PROMO-002: campaign START/END qua Redis ZSET + DB fallback. Worker re-read
 * PostgreSQL (ID, revision, status, window) trước khi phát; outbox commit trước ZREM;
 * START xử lý sau end_time chỉ phát INACTIVE (không phát ACTIVE muộn). Phát theo
 * lô rule nhỏ, mỗi lô một transaction, dedup key kiểm trước khi lock (ADR-021).
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
    private final MeterRegistry meters;

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

    /** Kết quả xử lý một schedule member; chỉ {@link #INCOMPLETE} giữ member lại trong ZSET để retry. */
    private enum MemberOutcome {
        /** Không còn việc: campaign mất, chưa tới mốc (member sớm) — dọn member. */
        SKIPPED,
        /** Member lỗi thời (revision/status đổi) — dọn member, không phát gì thêm. */
        STALE,
        /** Mọi lô đã commit hoặc đã có sẵn — dọn member. */
        EMITTED,
        /** Có lô lỗi: giữ member, vòng sau chạy lại; lô đã commit bị bỏ qua nhờ dedup key. */
        INCOMPLETE
    }

    private record PageResult(boolean stale, int emitted, int alreadyEmitted) {
        static final PageResult STALE = new PageResult(true, 0, 0);
    }

    /**
     * Một vòng loop (ADR-021): lấy due member, re-read DB, phát snapshot theo từng lô rule với mỗi lô một
     * transaction riêng, chỉ ZREM khi mọi lô đã xong (hoặc member lỗi thời).
     */
    public int processDue() {
        long nowMs = clock.millis();
        int processed = 0;
        for (ScheduleIndex.Member member : schedule.due(nowMs, properties.getSchedule().getBatchSize())) {
            try {
                MemberOutcome outcome = process(member);
                if (outcome != MemberOutcome.INCOMPLETE) {
                    schedule.remove(member.raw());
                }
                if (outcome == MemberOutcome.EMITTED) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.warn("Schedule member {} failed, will retry: {}", member.raw(), e.getMessage());
            }
        }
        return processed;
    }

    private MemberOutcome process(ScheduleIndex.Member member) {
        Optional<CampaignEntity> found = campaigns.findById(member.campaignId());
        if (found.isEmpty()) {
            return MemberOutcome.SKIPPED;
        }
        CampaignEntity campaign = found.get();
        if (isStale(campaign, member)) {
            log.info("Stale schedule member {} (revision={}, status={}): no-op", member.raw(),
                    campaign.getRevision(), campaign.getPublicationStatus());
            return MemberOutcome.STALE;
        }
        Instant now = clock.instant();
        if (member.trigger() == ScheduleTrigger.START && now.isBefore(campaign.getStartTime())) {
            return MemberOutcome.SKIPPED;
        }
        if (member.trigger() == ScheduleTrigger.END && now.isBefore(campaign.getEndTime())) {
            return MemberOutcome.SKIPPED;
        }
        return emitInBatches(member);
    }

    private static boolean isStale(CampaignEntity campaign, ScheduleIndex.Member member) {
        return campaign.getRevision() != member.revision()
                || campaign.getPublicationStatus() != PublicationStatus.PUBLISHED;
    }

    /**
     * Duyệt rule của campaign bằng keyset {@code id > afterId}; mỗi lô là một transaction riêng nên lock
     * product chỉ giữ trong lô. Lô lỗi được ghi nhận rồi bỏ qua để các lô sau vẫn được phát (một product
     * hỏng không chặn cả campaign); member chỉ được dọn khi không còn lô lỗi.
     */
    private MemberOutcome emitInBatches(ScheduleIndex.Member member) {
        int batchSize = properties.getSchedule().getEmitBatchSize();
        long afterId = 0;
        int pages = 0;
        int failedPages = 0;
        int emitted = 0;
        int alreadyEmitted = 0;
        while (true) {
            List<MarkdownRuleEntity> page = rules.pageAfter(member.campaignId(), afterId, PageRequest.of(0, batchSize));
            if (page.isEmpty()) {
                break;
            }
            long firstRuleId = page.get(0).getId();
            afterId = page.get(page.size() - 1).getId();
            pages++;
            try {
                PageResult result = transactionTemplate.execute(status -> emitPage(member, page));
                if (result == null || result.stale()) {
                    log.info("Schedule member {} became stale while emitting; stop after {} page(s)", member.raw(), pages);
                    count("stale", 1);
                    return MemberOutcome.STALE;
                }
                emitted += result.emitted();
                alreadyEmitted += result.alreadyEmitted();
                count("committed", 1);
                countSnapshots("emitted", result.emitted());
                countSnapshots("already_emitted", result.alreadyEmitted());
            } catch (RuntimeException e) {
                failedPages++;
                count("failed", 1);
                log.warn("Schedule member {} page starting at rule {} failed and rolled back, continuing: {}",
                        member.raw(), firstRuleId, e.getMessage());
            }
            if (page.size() < batchSize) {
                break;
            }
        }
        if (failedPages > 0) {
            log.warn("Schedule member {} incomplete: {}/{} page(s) failed (emitted={}, already_emitted={}); will retry",
                    member.raw(), failedPages, pages, emitted, alreadyEmitted);
            return MemberOutcome.INCOMPLETE;
        }
        log.info("Schedule member {} done: pages={} emitted={} already_emitted={}", member.raw(), pages, emitted,
                alreadyEmitted);
        return MemberOutcome.EMITTED;
    }

    /** Một lô trong một transaction: kiểm lại revision/status campaign, rồi phát với dedup pre-check. */
    private PageResult emitPage(ScheduleIndex.Member member, List<MarkdownRuleEntity> page) {
        CampaignEntity campaign = campaigns.findById(member.campaignId()).orElse(null);
        if (campaign == null || isStale(campaign, member)) {
            return PageResult.STALE;
        }
        List<UUID> productIds = page.stream()
                .filter(rule -> !rule.isInvalidated())
                .map(MarkdownRuleEntity::getProductId)
                .toList();
        MarkdownSnapshotService.BatchResult result =
                snapshots.emitBatch(productIds, campaign, member.revision(), member.trigger());
        return new PageResult(false, result.emitted(), result.alreadyEmitted());
    }

    private void count(String outcome, int amount) {
        meters.counter("promotion.schedule.emit.pages", "outcome", outcome).increment(amount);
    }

    private void countSnapshots(String result, int amount) {
        if (amount > 0) {
            meters.counter("promotion.schedule.emit.snapshots", "result", result).increment(amount);
        }
    }

    /**
     * Đường admin (publish/pause/cancel): chạy trong transaction của lệnh admin để nguyên tử với đổi
     * trạng thái campaign nên không chia lô. Phát snapshot cho mọi product có rule chưa invalidated; dedup theo cause.
     */
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
