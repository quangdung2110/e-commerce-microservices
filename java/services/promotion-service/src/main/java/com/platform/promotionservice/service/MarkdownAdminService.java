package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.CatalogProductTombstoneRepository;
import com.platform.promotionservice.infrastructure.persistence.MarkdownRuleRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.product.ProductCatalogClient;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MarkdownDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin markdown product-scope (openapi tag Admin markdown rules). Sau synchronous
 * Product validation, transaction acquire product advisory lock và reject nếu terminal
 * tombstone watermark đã tồn tại (PROMO-INV-018). Mutation chỉ ở DRAFT.
 */
@Service
@RequiredArgsConstructor
public class MarkdownAdminService {

    private final CampaignRepository campaigns;
    private final MarkdownRuleRepository rules;
    private final CatalogProductTombstoneRepository tombstones;
    private final ProductCatalogClient productCatalog;
    private final PromotionJdbc jdbc;
    private final ObjectMapper objectMapper;

    public record Loaded(MarkdownRuleEntity rule, CampaignEntity campaign) {
        public MarkdownDtos.MarkdownRuleResponse toResponse() {
            return MarkdownDtos.MarkdownRuleResponse.from(rule, campaign.getRevision());
        }
    }

    @Transactional(readOnly = true)
    public MarkdownDtos.MarkdownRulePage list(String campaignId, String cursor, Integer limitParam) {
        CampaignEntity campaign = campaigns.findById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
        int limit = AdminSupport.limitOf(limitParam);
        List<MarkdownRuleEntity> page = rules.pageAfter(campaign.getId(), AdminSupport.decodeCursor(cursor),
                PageRequest.of(0, limit + 1));
        boolean hasMore = page.size() > limit;
        List<MarkdownRuleEntity> window = hasMore ? page.subList(0, limit) : page;
        List<MarkdownDtos.MarkdownRuleResponse> items = window.stream()
                .map(rule -> MarkdownDtos.MarkdownRuleResponse.from(rule, campaign.getRevision())).toList();
        String next = hasMore ? AdminSupport.encodeCursor(window.get(window.size() - 1).getId()) : null;
        return new MarkdownDtos.MarkdownRulePage(items, next);
    }

    @Transactional(readOnly = true)
    public Loaded get(String ruleId) {
        MarkdownRuleEntity rule = rules.findById(Ids.parse(ruleId, "markdown_rule_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.MARKDOWN_RULE_NOT_FOUND, "Markdown rule not found"));
        return new Loaded(rule, campaigns.findById(rule.getCampaignId()).orElseThrow());
    }

    @Transactional
    public Loaded create(String campaignId, String ifMatch, MarkdownDtos.CreateMarkdownRuleRequest request) {
        CampaignEntity campaign = campaigns.lockById(Ids.parse(campaignId, "campaign_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found"));
        AdminSupport.requireIfMatch(campaign, ifMatch);
        AdminSupport.requireDraft(campaign);
        guardProduct(request.productId());
        if (rules.findByCampaignIdAndProductId(campaign.getId(), request.productId()).isPresent()) {
            throw new DomainException(ErrorCode.MARKDOWN_RULE_ALREADY_EXISTS,
                    "Campaign already has a markdown rule for product " + request.productId());
        }
        Instant now = jdbc.dbNow();
        MarkdownRuleEntity rule = new MarkdownRuleEntity();
        rule.setCampaignId(campaign.getId());
        rule.setProductId(request.productId());
        applyDiscount(rule, request.discount());
        rule.setPriority(request.priority());
        rule.setEnabled(request.enabled());
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        rules.save(rule);
        campaign.bumpRevision(CallerContext.actor(), now);
        return new Loaded(rule, campaign);
    }

    @Transactional
    public Loaded update(String ruleId, String ifMatch, JsonNode patch) {
        AdminSupport.requireNonEmptyPatch(patch);
        MarkdownRuleEntity rule = rules.findById(Ids.parse(ruleId, "markdown_rule_id"))
                .orElseThrow(() -> new DomainException(ErrorCode.MARKDOWN_RULE_NOT_FOUND, "Markdown rule not found"));
        CampaignEntity campaign = campaigns.lockById(rule.getCampaignId()).orElseThrow();
        AdminSupport.requireIfMatch(campaign, ifMatch);
        AdminSupport.requireDraft(campaign);
        guardProduct(rule.getProductId());
        if (AdminSupport.present(patch, "discount")) {
            try {
                applyDiscount(rule, objectMapper.treeToValue(patch.get("discount"), DiscountDto.class));
            } catch (DomainException e) {
                throw e;
            } catch (Exception e) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "discount is not valid");
            }
        }
        if (AdminSupport.present(patch, "priority")) {
            JsonNode priority = patch.get("priority");
            if (!priority.isInt()) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "priority must be a 32-bit integer");
            }
            rule.setPriority(priority.asInt());
        }
        if (AdminSupport.present(patch, "is_enabled")) {
            JsonNode enabled = patch.get("is_enabled");
            if (!enabled.isBoolean()) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "is_enabled must be boolean");
            }
            rule.setEnabled(enabled.asBoolean());
        }
        Instant now = jdbc.dbNow();
        rule.setUpdatedAt(now);
        campaign.bumpRevision(CallerContext.actor(), now);
        return new Loaded(rule, campaign);
    }

    /** Product validation đồng bộ rồi advisory lock + tombstone watermark guard. */
    private void guardProduct(UUID productId) {
        if (!productCatalog.exists(productId)) {
            throw new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + productId + " does not exist");
        }
        jdbc.lockProduct(productId);
        if (tombstones.existsById(productId)) {
            throw new DomainException(ErrorCode.PRODUCT_TOMBSTONED, "Product " + productId + " has been deleted");
        }
    }

    private static void applyDiscount(MarkdownRuleEntity rule, DiscountDto discount) {
        DiscountDto.Storage storage = discount.toStorage("discount");
        rule.setDiscountType(storage.type());
        rule.setDiscountValue(storage.value());
        rule.setMaxDiscountVnd(storage.maxDiscountVnd());
    }
}
