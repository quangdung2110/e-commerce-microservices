package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.InvalidationReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO cho Admin markdown rules (product-scope). */
public final class MarkdownDtos {

    private MarkdownDtos() {
    }

    public record CreateMarkdownRuleRequest(
            @NotNull UUID productId,
            @NotNull @Valid DiscountDto discount,
            @NotNull Integer priority,
            @JsonProperty("is_enabled") @NotNull Boolean enabled) {
    }

    public record MarkdownRuleResponse(
            String id,
            @JsonProperty("campaign_id") String campaignId,
            @JsonProperty("campaign_revision") long campaignRevision,
            @JsonProperty("product_id") UUID productId,
            DiscountDto discount,
            int priority,
            @JsonProperty("is_enabled") boolean enabled,
            @JsonProperty("invalidated_at") Instant invalidatedAt,
            @JsonProperty("invalidation_reason") InvalidationReason invalidationReason,
            @JsonProperty("source_product_revision") Long sourceProductRevision,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {

        public static MarkdownRuleResponse from(MarkdownRuleEntity rule, long campaignRevision) {
            return new MarkdownRuleResponse(
                    Long.toString(rule.getId()),
                    Long.toString(rule.getCampaignId()),
                    campaignRevision,
                    rule.getProductId(),
                    DiscountDto.fromStorage(rule.getDiscountType(), rule.getDiscountValue(), rule.getMaxDiscountVnd()),
                    rule.getPriority(),
                    rule.isEnabled(),
                    rule.getInvalidatedAt(),
                    rule.getInvalidationReason(),
                    rule.getSourceProductRevision(),
                    rule.getCreatedAt(),
                    rule.getUpdatedAt());
        }
    }

    public record MarkdownRulePage(List<MarkdownRuleResponse> items, String nextCursor) {
    }
}
