package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.entity.PromotionBudgetEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.CatalogProductTombstoneRepository;
import com.platform.promotionservice.infrastructure.persistence.CouponClaimRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionBudgetRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.web.dto.CampaignDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WF-PROMO-001: quản trị campaign, clone aggregate và publication transition.
 * Mọi mutation tăng {@code campaigns.revision}; published definition bất biến.
 * Side effect Redis (schedule, claim gate, cache) chỉ chạy sau commit; DB
 * fallback/startup rebuild tự khôi phục nếu side effect lỗi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignAdminService {

    private final CampaignRepository campaigns;
    private final PromotionRepository promotions;
    private final PromotionBudgetRepository budgets;
    private final CouponClaimRepository claims;
    private final MarkdownRuleRepository markdownRules;
    private final CatalogProductTombstoneRepository tombstones;
    private final PromotionJdbc jdbc;
    private final CampaignScheduleService schedule;
    private final PromotionSnapshotService snapshots;
    private final RuntimeRebuildService runtimeRebuild;
    private final RuntimeStore runtime;
    private final PromotionProperties properties;

    @Transactional(readOnly = true)
    public CampaignDtos.CampaignPage list(PublicationStatus status, CampaignPhase phase, String cursor, Integer limitParam) {
        int limit = AdminSupport.limitOf(limitParam);
        long after = AdminSupport.decodeCursor(cursor);
        Instant now = jdbc.dbNow();
        // Keyset theo id; phase là thuộc tính suy ra nên lọc sau khi đọc trang.
        List<CampaignEntity> collected = new ArrayList<>();
        boolean exhausted = false;
        while (collected.size() <= limit && !exhausted) {
            List<CampaignEntity> page = campaigns.pageAfter(status, after, PageRequest.of(0, limit + 1));
            if (page.isEmpty()) {
                break;
            }
            for (CampaignEntity campaign : page) {
                after = campaign.getId();
                if (phase == null || campaign.phaseAt(now) == phase) {
                    collected.add(campaign);
                }
                if (collected.size() > limit) {
                    break;
                }
            }
            exhausted = page.size() < limit + 1;
        }
        boolean hasMore = collected.size() > limit;
        List<CampaignEntity> window = hasMore ? collected.subList(0, limit) : collected;
        List<CampaignDtos.CampaignResponse> items = window.stream()
                .map(campaign -> CampaignDtos.CampaignResponse.from(campaign, now)).toList();
        String nextCursor = hasMore ? AdminSupport.encodeCursor(window.get(window.size() - 1).getId()) : null;
        return new CampaignDtos.CampaignPage(items, nextCursor);
    }

    @Transactional
    public CampaignEntity create(CampaignDtos.CreateCampaignRequest request) {
        AdminSupport.requireWindow(request.startTime(), request.endTime());
        if (campaigns.existsByCode(request.code())) {
            throw new DomainException(ErrorCode.CAMPAIGN_CODE_ALREADY_EXISTS, "Campaign code already exists");
        }
        Instant now = jdbc.dbNow();
        String actor = CallerContext.actor();
        CampaignEntity campaign = new CampaignEntity();
        campaign.setCode(request.code());
        campaign.setName(request.name());
        campaign.setDescription(request.description());
        campaign.setPublicationStatus(PublicationStatus.DRAFT);
        campaign.setStartTime(request.startTime());
        campaign.setEndTime(request.endTime());
        campaign.setRevision(1);
        campaign.setCreatedBy(actor);
        campaign.setUpdatedBy(actor);
        campaign.setCreatedAt(now);
        campaign.setUpdatedAt(now);
        return campaigns.save(campaign);
    }

    @Transactional(readOnly = true)
    public CampaignEntity get(String campaignId) {
        return campaigns.findById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
    }

    @Transactional(readOnly = true)
    public Instant now() {
        return jdbc.dbNow();
    }

    /** Sửa metadata/lịch khi còn DRAFT; If-Match parent revision. */
    @Transactional
    public CampaignEntity update(String campaignId, String ifMatch, JsonNode patch) {
        AdminSupport.requireNonEmptyPatch(patch);
        CampaignEntity campaign = lock(campaignId);
        AdminSupport.requireIfMatch(campaign, ifMatch);
        AdminSupport.requireDraft(campaign);
        if (AdminSupport.present(patch, "name")) {
            campaign.setName(AdminSupport.text(patch, "name", 255, false));
        }
        if (AdminSupport.present(patch, "description")) {
            campaign.setDescription(AdminSupport.text(patch, "description", 4000, true));
        }
        if (AdminSupport.present(patch, "start_time")) {
            campaign.setStartTime(AdminSupport.instant(patch, "start_time"));
        }
        if (AdminSupport.present(patch, "end_time")) {
            campaign.setEndTime(AdminSupport.instant(patch, "end_time"));
        }
        AdminSupport.requireWindow(campaign.getStartTime(), campaign.getEndTime());
        campaign.bumpRevision(CallerContext.actor(), jdbc.dbNow());
        return campaign;
    }

    /** Clone aggregate thành campaign DRAFT với campaign/promotion/markdown ID mới; source bất biến. */
    @Transactional
    public CampaignEntity clone(String sourceId, String ifMatch, CampaignDtos.CloneCampaignRequest request) {
        CampaignEntity source = get(sourceId);
        AdminSupport.requireIfMatch(source, ifMatch);
        AdminSupport.requireWindow(request.startTime(), request.endTime());
        if (campaigns.existsByCode(request.code())) {
            throw new DomainException(ErrorCode.CAMPAIGN_CODE_ALREADY_EXISTS, "Campaign code already exists");
        }
        List<PromotionEntity> sourcePromotions = promotions.findByCampaignIdOrderByIdAsc(source.getId());
        Map<Long, String> overrides = new HashMap<>();
        for (CampaignDtos.PromotionCodeOverride override : request.promotionCodeOverrides()) {
            overrides.put(Ids.parse(override.sourcePromotionId(), "promotion_code_overrides[].source_promotion_id"),
                    override.newCode());
        }
        if (overrides.size() != sourcePromotions.size()
                || sourcePromotions.stream().anyMatch(p -> !overrides.containsKey(p.getId()))) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "promotion_code_overrides must cover exactly every promotion of the source campaign");
        }
        for (String code : overrides.values()) {
            if (promotions.existsByCode(code)) {
                throw new DomainException(ErrorCode.PROMOTION_CODE_ALREADY_EXISTS, "Promotion code already exists: " + code);
            }
        }

        Instant now = jdbc.dbNow();
        String actor = CallerContext.actor();
        CampaignEntity target = new CampaignEntity();
        target.setCode(request.code());
        target.setName(request.name());
        target.setDescription(request.description());
        target.setPublicationStatus(PublicationStatus.DRAFT);
        target.setStartTime(request.startTime());
        target.setEndTime(request.endTime());
        target.setRevision(1);
        target.setCreatedBy(actor);
        target.setUpdatedBy(actor);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        campaigns.save(target);

        for (PromotionEntity src : sourcePromotions) {
            PromotionEntity copy = new PromotionEntity();
            copy.setCampaignId(target.getId());
            copy.setCode(overrides.get(src.getId()));
            copy.setName(src.getName());
            copy.setDescription(src.getDescription());
            copy.setDistributionMode(src.getDistributionMode());
            copy.setDiscountType(src.getDiscountType());
            copy.setDiscountValue(src.getDiscountValue());
            copy.setMaxDiscountVnd(src.getMaxDiscountVnd());
            copy.setMinOrderAmountVnd(src.getMinOrderAmountVnd());
            copy.setConditionsRule(src.getConditionsRule());
            copy.setRuleSchemaVersion(src.getRuleSchemaVersion());
            copy.setValidityMode(src.getValidityMode());
            copy.setValidForSeconds(src.getValidForSeconds());
            copy.setRedeemUntil(src.getRedeemUntil());
            copy.setEnabled(src.isEnabled());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            promotions.save(copy);
            PromotionBudgetEntity srcBudget = budgets.findById(src.getId()).orElseThrow();
            PromotionBudgetEntity budget = new PromotionBudgetEntity();
            budget.setPromotionId(copy.getId());
            budget.setTotalClaimQuantity(srcBudget.getTotalClaimQuantity());
            budget.setTotalRedemptionQuantity(srcBudget.getTotalRedemptionQuantity());
            budget.setTotalBudgetVnd(srcBudget.getTotalBudgetVnd());
            budget.setUpdatedAt(now);
            budgets.save(budget);
        }
        for (MarkdownRuleEntity src : markdownRules.findByCampaignIdOrderByIdAsc(source.getId())) {
            if (src.isInvalidated()) {
                continue;
            }
            jdbc.lockProduct(src.getProductId());
            if (tombstones.existsById(src.getProductId())) {
                continue;
            }
            MarkdownRuleEntity copy = new MarkdownRuleEntity();
            copy.setCampaignId(target.getId());
            copy.setProductId(src.getProductId());
            copy.setDiscountType(src.getDiscountType());
            copy.setDiscountValue(src.getDiscountValue());
            copy.setMaxDiscountVnd(src.getMaxDiscountVnd());
            copy.setPriority(src.getPriority());
            copy.setEnabled(src.isEnabled());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            markdownRules.save(copy);
        }
        return target;
    }

    /** Publish/pause/cancel: DRAFT→PUBLISHED, PUBLISHED→PAUSED|CANCELLED, PAUSED→CANCELLED. */
    @Transactional
    public CampaignEntity transition(String campaignId, String ifMatch, CampaignDtos.CampaignTransitionRequest request) {
        CampaignEntity campaign = lock(campaignId);
        AdminSupport.requireIfMatch(campaign, ifMatch);
        PublicationStatus from = campaign.getPublicationStatus();
        PublicationStatus to = request.targetStatus();
        boolean allowed = (from == PublicationStatus.DRAFT && to == PublicationStatus.PUBLISHED)
                || (from == PublicationStatus.PUBLISHED && (to == PublicationStatus.PAUSED || to == PublicationStatus.CANCELLED))
                || (from == PublicationStatus.PAUSED && to == PublicationStatus.CANCELLED);
        if (!allowed) {
            throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transition " + from + " -> " + to + " is not allowed");
        }
        Instant now = jdbc.dbNow();
        List<PromotionEntity> children = promotions.findByCampaignIdOrderByIdAsc(campaign.getId());
        boolean wasActive = campaign.isActiveAt(now);

        if (to == PublicationStatus.PUBLISHED) {
            if (!now.isBefore(campaign.getEndTime())) {
                throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION, "Cannot publish a campaign whose end_time has passed");
            }
            for (PromotionEntity promotion : children) {
                pregenerateClaims(promotion, now);
            }
        } else {
            // Đóng claim gate bảo thủ trước khi DB commit; rollback thì rebuild từ DB (WF-PROMO-001).
            List<Long> limitedClaimIds = children.stream()
                    .filter(p -> p.getDistributionMode() == DistributionMode.LIMITED_CLAIM)
                    .map(PromotionEntity::getId)
                    .toList();
            if (!limitedClaimIds.isEmpty()) {
                // Đăng ký trước vòng đóng: nếu một gate đóng được rồi gate sau lỗi, rollback vẫn mở lại các gate đã đóng.
                AdminSupport.afterRollback(() -> reopenGates(campaign.getId(), limitedClaimIds));
            }
            for (Long promotionId : limitedClaimIds) {
                try {
                    runtime.closeClaimGate(promotionId);
                } catch (RuntimeException e) {
                    // Không đóng được gate thì không được pause/cancel: claim vẫn có thể được cấp sau khi admin
                    // tưởng đã dừng. Redis lỗi là tạm thời nên 503 retryable; DB chưa đổi gì.
                    log.warn("Could not close claim gate for promotion {}; aborting {}: {}", promotionId, to,
                            e.getMessage());
                    throw new DomainException(ErrorCode.PROMOTION_UNAVAILABLE,
                            "Claim gate of promotion " + promotionId + " could not be closed; campaign not " + to);
                }
            }
        }

        campaign.setPublicationStatus(to);
        campaign.bumpRevision(CallerContext.actor(), now);
        campaigns.saveAndFlush(campaign);
        log.info("Campaign {} {} -> {} (revision {}), reason: {}", campaign.getId(), from, to,
                campaign.getRevision(), request.reason());

        if (to == PublicationStatus.PUBLISHED && campaign.isActiveAt(now)) {
            schedule.emitForCampaign(campaign, campaign.getRevision(), ScheduleTrigger.PUBLISH);
        } else if (to != PublicationStatus.PUBLISHED && wasActive) {
            schedule.emitForCampaign(campaign, campaign.getRevision(),
                    to == PublicationStatus.PAUSED ? ScheduleTrigger.PAUSE : ScheduleTrigger.CANCEL);
        }

        long revision = campaign.getRevision();
        List<Long> promotionIds = children.stream().map(PromotionEntity::getId).toList();
        AdminSupport.afterCommit(() -> afterTransition(campaign.getId(), promotionIds, revision, now));
        return campaign;
    }

    /** Pause/cancel rollback: DB vẫn ở trạng thái cũ nên mở lại gate từ PostgreSQL; lỗi Redis chỉ log (không fail-open). */
    private void reopenGates(long campaignId, List<Long> promotionIds) {
        for (Long promotionId : promotionIds) {
            try {
                runtimeRebuild.reopenAfterRollback(promotionId);
            } catch (RuntimeException e) {
                log.warn("Could not reopen claim gate after rollback for promotion {} (campaign {}): {}",
                        promotionId, campaignId, e.getMessage());
            }
        }
    }

    private void afterTransition(long campaignId, List<Long> promotionIds, long revision, Instant now) {
        try {
            campaigns.findById(campaignId).ifPresent(c -> schedule.scheduleBoundaries(c, now));
            for (Long promotionId : promotionIds) {
                runtimeRebuild.rebuildPromotion(promotionId);
            }
            snapshots.invalidateCampaign(campaignId);
        } catch (RuntimeException e) {
            log.warn("Post-commit runtime sync failed for campaign {} revision {}: {}", campaignId, revision,
                    e.getMessage());
        }
    }

    /** LIMITED_CLAIM: pre-generate đúng total_claim_quantity claim rows tại publish. */
    private void pregenerateClaims(PromotionEntity promotion, Instant now) {
        if (promotion.getDistributionMode() != DistributionMode.LIMITED_CLAIM) {
            return;
        }
        PromotionBudgetEntity budget = budgets.findById(promotion.getId()).orElseThrow();
        Long total = budget.getTotalClaimQuantity();
        if (total == null) {
            throw new DomainException(ErrorCode.INVALID_RULE,
                    "LIMITED_CLAIM promotion " + promotion.getCode() + " requires total_claim_quantity");
        }
        if (total > properties.getClaim().getMaxPoolSize()) {
            throw new DomainException(ErrorCode.INVALID_RULE,
                    "total_claim_quantity exceeds max pool size " + properties.getClaim().getMaxPoolSize());
        }
        long existing = claims.countByPromotionId(promotion.getId());
        long missing = total - existing;
        int batch = properties.getClaim().getPoolBatchSize();
        while (missing > 0) {
            int chunk = (int) Math.min(batch, missing);
            jdbc.insertAvailableClaims(promotion.getId(), chunk, now);
            missing -= chunk;
        }
    }

    private CampaignEntity lock(String campaignId) {
        return campaigns.lockById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
    }
}
