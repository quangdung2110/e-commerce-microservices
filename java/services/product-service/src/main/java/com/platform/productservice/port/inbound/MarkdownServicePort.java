package com.platform.productservice.port.inbound;

import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;

import java.util.Optional;

/**
 * Inbound port interface for markdown pricing management operations.
 * Defines the contract for applying, removing, and recalculating markdown prices.
 */
public interface MarkdownServicePort {

    /**
     * Applies a markdown discount to a SKU.
     *
     * @param application the markdown application details
     * @return the created or updated MarkdownApplied record
     */
    MarkdownApplied applyMarkdown(MarkdownApplication application);

    /**
     * Removes a markdown rule from all affected SKUs.
     *
     * @param ruleId the markdown rule ID
     */
    void removeMarkdown(String ruleId);

    /**
     * Recalculates the markdown price for an existing rule.
     *
     * @param ruleId the markdown rule ID
     * @return the recalculated MarkdownApplied records
     */
    java.util.List<MarkdownApplied> recalculateMarkdown(String ruleId);

    /**
     * Gets the active markdown for a SKU.
     *
     * @param skuId the SKU ID
     * @return the active markdown cache entry if present
     */
    Optional<MarkdownCacheEntry> getActiveMarkdown(String skuId);

    /**
     * Markdown application details.
     *
     * @param ruleId           unique rule identifier
     * @param productId        product ID
     * @param skuId            SKU ID
     * @param discountType     discount type (PERCENTAGE or FIXED_AMOUNT)
     * @param discountValue    discount value
     * @param maxDiscountAmount optional maximum discount cap
     * @param startTime        when the markdown becomes active
     * @param expiresAt        when the markdown expires
     */
    record MarkdownApplication(
            String ruleId,
            String productId,
            String skuId,
            com.platform.productservice.domain.enums.DiscountType discountType,
            java.math.BigDecimal discountValue,
            java.math.BigDecimal maxDiscountAmount,
            java.time.LocalDateTime startTime,
            java.time.LocalDateTime expiresAt
    ) {}
}
