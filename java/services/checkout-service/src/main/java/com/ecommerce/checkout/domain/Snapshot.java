package com.ecommerce.checkout.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * The immutable quotation of one token (domain.md, "Aggregate cuc bo"), stored envelope-encrypted in
 * the session hash. Money is integer VND. Nothing here is shared with another token or cached for
 * another user: it is the answer to "what did this token quote".
 *
 * <p>{@code category_ids}, {@code catalog_revision}, the allocation plan and both hashes are
 * internal and never appear in the public response (interfaces.md, "CheckoutPreview").
 */
public record Snapshot(
        int schemaVersion,
        String userId,
        Address address,
        List<Item> items,
        Allocation allocation,
        Promotion promotion,
        long totalAmount,
        long totalDiscount,
        long finalAmount,
        String itemsHash,
        String pricingHash,
        long createdAtMs,
        long expiresAtMs) {

    public static final int SCHEMA_VERSION = 1;

    public record Address(
            String addressId,
            String recipientName,
            String recipientPhone,
            String addressLine,
            String provinceCode,
            String districtCode,
            String wardCode) {
    }

    /** One quoted line; {@code lineReference} equals {@code skuId} in Checkout v1. */
    public record Item(
            String lineReference,
            String productId,
            String skuId,
            int quantity,
            long unitPrice,
            long listUnitPrice,
            List<String> categoryIds,
            String productName,
            Map<String, String> variantAttributes,
            String imageStorageKey,
            long catalogRevision) {

        @JsonIgnore
        public long lineTotal() {
            return Math.multiplyExact(unitPrice, (long) quantity);
        }
    }

    public record AllocationLine(String lineReference, String skuId, String warehouseId, int quantity) {
    }

    /** Inventory's advisory plan; Order Reserve revalidates it, Checkout never does. */
    public record Allocation(String allocationPlanId, List<AllocationLine> lines) {
    }

    /** {@code promotionId == null} means no voucher: revision 0, discount 0. */
    public record Promotion(String promotionId, long campaignRevision, long discount) {

        public static Promotion none() {
            return new Promotion(null, 0, 0);
        }
    }

    @JsonIgnore
    public boolean hasPromotion() {
        return promotion.promotionId() != null;
    }
}
