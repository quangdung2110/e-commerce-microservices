package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionBudgetEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.rule.RuleNode;
import com.platform.promotionservice.domain.rule.RuleValidator;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionBudgetRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MoneyDto;
import com.platform.promotionservice.web.dto.PromotionDtos;
import com.platform.promotionservice.web.dto.ValidityDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Admin voucher definition + budget (openapi tag Admin promotions). Mutation chỉ ở
 * campaign DRAFT, If-Match theo parent campaign revision, mọi mutation tăng parent revision.
 */
@Service
@RequiredArgsConstructor
public class PromotionAdminService {

    private final CampaignRepository campaigns;
    private final PromotionRepository promotions;
    private final PromotionBudgetRepository budgets;
    private final PromotionJdbc jdbc;
    private final ObjectMapper objectMapper;

    public record Loaded(PromotionEntity promotion, CampaignEntity campaign, PromotionBudgetEntity budget) {

        public PromotionDtos.PromotionResponse toResponse(ObjectMapper mapper) {
            try {
                RuleNode rule = mapper.readValue(promotion.getConditionsRule(), RuleNode.class);
                return PromotionDtos.PromotionResponse.from(promotion, campaign.getRevision(), budget, rule);
            } catch (Exception e) {
                throw new IllegalStateException("stored conditions_rule is unreadable", e);
            }
        }
    }

    @Transactional(readOnly = true)
    public PromotionDtos.PromotionPage list(String campaignId, String cursor, Integer limitParam) {
        CampaignEntity campaign = campaign(campaignId);
        int limit = AdminSupport.limitOf(limitParam);
        List<PromotionEntity> page = promotions.pageAfter(campaign.getId(), AdminSupport.decodeCursor(cursor),
                PageRequest.of(0, limit + 1));
        boolean hasMore = page.size() > limit;
        List<PromotionEntity> window = hasMore ? page.subList(0, limit) : page;
        List<PromotionDtos.PromotionResponse> items = window.stream()
                .map(p -> new Loaded(p, campaign, budgets.findById(p.getId()).orElseThrow()).toResponse(objectMapper))
                .toList();
        String next = hasMore ? AdminSupport.encodeCursor(window.get(window.size() - 1).getId()) : null;
        return new PromotionDtos.PromotionPage(items, next);
    }

    @Transactional(readOnly = true)
    public Loaded get(String promotionId) {
        PromotionEntity promotion = promotions.findById(Ids.parse(promotionId, "promotion_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        CampaignEntity campaign = campaigns.findById(promotion.getCampaignId()).orElseThrow();
        return new Loaded(promotion, campaign, budgets.findById(promotion.getId()).orElseThrow());
    }

    @Transactional
    public Loaded create(String campaignId, String ifMatch, PromotionDtos.CreatePromotionRequest request) {
        CampaignEntity campaign = campaigns.lockById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
        AdminSupport.requireIfMatch(campaign, ifMatch);
        AdminSupport.requireDraft(campaign);
        if (promotions.existsByCode(request.code())) {
            throw new DomainException(ErrorCode.PROMOTION_CODE_ALREADY_EXISTS, "Promotion code already exists");
        }
        Instant now = jdbc.dbNow();
        PromotionEntity promotion = new PromotionEntity();
        promotion.setCampaignId(campaign.getId());
        promotion.setCode(request.code());
        promotion.setName(request.name());
        promotion.setDescription(request.description());
        promotion.setDistributionMode(request.distributionMode());
        applyDiscount(promotion, request.discount());
        promotion.setMinOrderAmountVnd(request.minOrderAmount().requireVnd("min_order_amount", 0));
        applyRule(promotion, request.conditionsRule(), request.ruleSchemaVersion());
        applyValidity(promotion, request.validity(), campaign);
        promotion.setEnabled(request.enabled());
        promotion.setCreatedAt(now);
        promotion.setUpdatedAt(now);
        promotions.save(promotion);

        PromotionBudgetEntity budget = new PromotionBudgetEntity();
        budget.setPromotionId(promotion.getId());
        applyBudget(budget, request.budget(), promotion.getDistributionMode());
        budget.setUpdatedAt(now);
        budgets.save(budget);

        campaign.bumpRevision(CallerContext.actor(), now);
        return new Loaded(promotion, campaign, budget);
    }

    @Transactional
    public Loaded update(String promotionId, String ifMatch, JsonNode patch) {
        AdminSupport.requireNonEmptyPatch(patch);
        PromotionEntity promotion = promotions.findById(Ids.parse(promotionId, "promotion_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        CampaignEntity campaign = campaigns.lockById(promotion.getCampaignId()).orElseThrow();
        AdminSupport.requireIfMatch(campaign, ifMatch);
        AdminSupport.requireDraft(campaign);
        PromotionBudgetEntity budget = budgets.lockById(promotion.getId()).orElseThrow();
        Instant now = jdbc.dbNow();

        if (AdminSupport.present(patch, "name")) {
            promotion.setName(AdminSupport.text(patch, "name", 255, false));
        }
        if (AdminSupport.present(patch, "description")) {
            promotion.setDescription(AdminSupport.text(patch, "description", 4000, true));
        }
        if (AdminSupport.present(patch, "distribution_mode")) {
            promotion.setDistributionMode(convert(patch.get("distribution_mode"), DistributionMode.class, "distribution_mode"));
        }
        if (AdminSupport.present(patch, "discount")) {
            applyDiscount(promotion, convert(patch.get("discount"), DiscountDto.class, "discount"));
        }
        if (AdminSupport.present(patch, "min_order_amount")) {
            promotion.setMinOrderAmountVnd(convert(patch.get("min_order_amount"), MoneyDto.class, "min_order_amount")
                    .requireVnd("min_order_amount", 0));
        }
        if (AdminSupport.present(patch, "conditions_rule") || AdminSupport.present(patch, "rule_schema_version")) {
            RuleNode rule = AdminSupport.present(patch, "conditions_rule")
                    ? convert(patch.get("conditions_rule"), RuleNode.class, "conditions_rule")
                    : convert(objectMapper.valueToTree(readRule(promotion)), RuleNode.class, "conditions_rule");
            int version = AdminSupport.present(patch, "rule_schema_version")
                    ? patch.get("rule_schema_version").asInt() : promotion.getRuleSchemaVersion();
            applyRule(promotion, rule, version);
        }
        if (AdminSupport.present(patch, "validity")) {
            applyValidity(promotion, convert(patch.get("validity"), ValidityDto.class, "validity"), campaign);
        }
        if (AdminSupport.present(patch, "is_enabled")) {
            JsonNode enabled = patch.get("is_enabled");
            if (!enabled.isBoolean()) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "is_enabled must be boolean");
            }
            promotion.setEnabled(enabled.asBoolean());
        }
        if (AdminSupport.present(patch, "budget")) {
            applyBudget(budget, convert(patch.get("budget"), PromotionDtos.BudgetDefinition.class, "budget"),
                    promotion.getDistributionMode());
            budget.setUpdatedAt(now);
        } else if (AdminSupport.present(patch, "distribution_mode")) {
            validateBudgetForMode(budget.getTotalClaimQuantity(), promotion.getDistributionMode());
        }
        promotion.setUpdatedAt(now);
        campaign.bumpRevision(CallerContext.actor(), now);
        return new Loaded(promotion, campaign, budget);
    }

    private RuleNode readRule(PromotionEntity promotion) {
        try {
            return objectMapper.readValue(promotion.getConditionsRule(), RuleNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored conditions_rule is unreadable", e);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type, String field) {
        if (node == null || node.isNull()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must not be null");
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " is not valid: " + rootMessage(e));
        }
    }

    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? "malformed" : message.split("\n")[0];
    }

    private static void applyDiscount(PromotionEntity promotion, DiscountDto discount) {
        DiscountDto.Storage storage = discount.toStorage("discount");
        promotion.setDiscountType(storage.type());
        promotion.setDiscountValue(storage.value());
        promotion.setMaxDiscountVnd(storage.maxDiscountVnd());
    }

    private void applyRule(PromotionEntity promotion, RuleNode rule, int schemaVersion) {
        RuleValidator.validate(rule, schemaVersion);
        try {
            promotion.setConditionsRule(objectMapper.writeValueAsString(rule));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize rule", e);
        }
        promotion.setRuleSchemaVersion((short) schemaVersion);
    }

    private static void applyValidity(PromotionEntity promotion, ValidityDto validity, CampaignEntity campaign) {
        ValidityDto.Storage storage = validity.toStorage();
        if (storage.redeemUntil() != null && !storage.redeemUntil().isAfter(campaign.getStartTime())) {
            throw new DomainException(ErrorCode.INVALID_RULE, "validity.redeem_until must be after campaign start_time");
        }
        promotion.setValidityMode(storage.mode());
        promotion.setValidForSeconds(storage.validForSeconds());
        promotion.setRedeemUntil(storage.redeemUntil());
    }

    private static void applyBudget(PromotionBudgetEntity budget, PromotionDtos.BudgetDefinition definition,
                                    DistributionMode mode) {
        if (definition == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "budget is required");
        }
        requirePositiveOrNull(definition.totalClaimQuantity(), "budget.total_claim_quantity");
        requirePositiveOrNull(definition.totalRedemptionQuantity(), "budget.total_redemption_quantity");
        validateBudgetForMode(definition.totalClaimQuantity(), mode);
        budget.setTotalClaimQuantity(definition.totalClaimQuantity());
        budget.setTotalRedemptionQuantity(definition.totalRedemptionQuantity());
        budget.setTotalBudgetVnd(MoneyDto.nullableVnd(definition.totalBudget(), "budget.total_budget", 1));
    }

    private static void validateBudgetForMode(Long totalClaimQuantity, DistributionMode mode) {
        if (mode == DistributionMode.LIMITED_CLAIM && totalClaimQuantity == null) {
            throw new DomainException(ErrorCode.INVALID_RULE, "LIMITED_CLAIM requires budget.total_claim_quantity",
                    List.of(new DomainException.FieldError("budget.total_claim_quantity", "is required for LIMITED_CLAIM")));
        }
    }

    private static void requirePositiveOrNull(Long value, String field) {
        if (value != null && value < 1) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must be >= 1 or null");
        }
    }

    private CampaignEntity campaign(String campaignId) {
        return campaigns.findById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
    }
}
