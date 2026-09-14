package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.domain.entity.PromotionBudgetEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.rule.RuleNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** DTO cho Admin promotions (voucher definition + budget). */
public final class PromotionDtos {

    private PromotionDtos() {
    }

    public record BudgetDefinition(
            @JsonProperty("total_claim_quantity") Long totalClaimQuantity,
            @JsonProperty("total_redemption_quantity") Long totalRedemptionQuantity,
            @JsonProperty("total_budget") MoneyDto totalBudget) {
    }

    public record CreatePromotionRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = CampaignDtos.CODE_PATTERN) String code,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description,
            @NotNull DistributionMode distributionMode,
            @NotNull @Valid DiscountDto discount,
            @NotNull MoneyDto minOrderAmount,
            @NotNull RuleNode conditionsRule,
            @NotNull Integer ruleSchemaVersion,
            @NotNull ValidityDto validity,
            @JsonProperty("is_enabled") @NotNull Boolean enabled,
            @NotNull BudgetDefinition budget) {
    }

    public record GrantVoucherRequest(
            @NotBlank String userId,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record BudgetResponse(
            @JsonProperty("total_claim_quantity") Long totalClaimQuantity,
            @JsonProperty("claimed_quantity") long claimedQuantity,
            @JsonProperty("total_redemption_quantity") Long totalRedemptionQuantity,
            @JsonProperty("used_quantity") long usedQuantity,
            @JsonProperty("total_budget") MoneyDto totalBudget,
            @JsonProperty("used_budget") MoneyDto usedBudget,
            @JsonProperty("updated_at") Instant updatedAt) {

        public static BudgetResponse from(PromotionBudgetEntity budget) {
            return new BudgetResponse(
                    budget.getTotalClaimQuantity(),
                    budget.getClaimedQuantity(),
                    budget.getTotalRedemptionQuantity(),
                    budget.getUsedQuantity(),
                    MoneyDto.ofNullable(budget.getTotalBudgetVnd()),
                    MoneyDto.vnd(budget.getUsedBudgetVnd()),
                    budget.getUpdatedAt());
        }
    }

    public record PromotionResponse(
            String id,
            @JsonProperty("campaign_id") String campaignId,
            @JsonProperty("campaign_revision") long campaignRevision,
            String code,
            String name,
            String description,
            @JsonProperty("distribution_mode") DistributionMode distributionMode,
            DiscountDto discount,
            @JsonProperty("min_order_amount") MoneyDto minOrderAmount,
            @JsonProperty("conditions_rule") RuleNode conditionsRule,
            @JsonProperty("rule_schema_version") int ruleSchemaVersion,
            ValidityDto validity,
            @JsonProperty("is_enabled") boolean enabled,
            BudgetResponse budget,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {

        public static PromotionResponse from(PromotionEntity promotion, long campaignRevision,
                                             PromotionBudgetEntity budget, RuleNode rule) {
            return new PromotionResponse(
                    Long.toString(promotion.getId()),
                    Long.toString(promotion.getCampaignId()),
                    campaignRevision,
                    promotion.getCode(),
                    promotion.getName(),
                    promotion.getDescription(),
                    promotion.getDistributionMode(),
                    DiscountDto.fromStorage(promotion.getDiscountType(), promotion.getDiscountValue(),
                            promotion.getMaxDiscountVnd()),
                    MoneyDto.vnd(promotion.getMinOrderAmountVnd()),
                    rule,
                    promotion.getRuleSchemaVersion(),
                    ValidityDto.fromStorage(promotion.getValidityMode(), promotion.getValidForSeconds(),
                            promotion.getRedeemUntil()),
                    promotion.isEnabled(),
                    BudgetResponse.from(budget),
                    promotion.getCreatedAt(),
                    promotion.getUpdatedAt());
        }
    }

    public record PromotionPage(List<PromotionResponse> items, String nextCursor) {
    }
}
