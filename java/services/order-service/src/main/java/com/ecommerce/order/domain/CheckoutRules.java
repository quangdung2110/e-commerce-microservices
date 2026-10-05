package com.ecommerce.order.domain;

import com.ecommerce.order.common.ApiException.FieldError;
import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.web.dto.CheckoutDtos.AllocationPlanLine;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutItem;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.web.dto.CheckoutDtos.PromotionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-field validation of a Checkout snapshot before any write (WF-ORDER-004
 * preconditions, interfaces.md §2.2): unique line references/SKUs, one-to-one allocation
 * plan, amount invariants (INV-ORD-010), PLATFORM voucher cardinality and both canonical
 * hashes recomputed with RFC 8785 + SHA-256.
 */
public final class CheckoutRules {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CheckoutRules() {
    }

    public static List<FieldError> validate(CheckoutOrderSubmissionRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (Boolean.TRUE.equals(request.userAckDegradedPricing())) {
            errors.add(new FieldError("user_ack_degraded_pricing", "must be false"));
        }
        Set<String> references = new HashSet<>();
        Set<String> skus = new HashSet<>();
        long gross = 0;
        for (CheckoutItem item : request.items()) {
            if (!references.add(item.lineReference())) {
                errors.add(new FieldError("items", "line_reference must be unique"));
            }
            if (!skus.add(item.skuId())) {
                errors.add(new FieldError("items", "sku_id must be unique"));
            }
            if (!item.lineReference().equals(item.skuId())) {
                errors.add(new FieldError("items", "line_reference must equal sku_id in Checkout v1"));
            }
            gross = Math.addExact(gross, Math.multiplyExact(item.unitPrice().amountMinor(), (long) item.quantity()));
        }
        Map<String, AllocationPlanLine> plan = new HashMap<>();
        for (AllocationPlanLine line : request.allocationPlan().lines()) {
            if (plan.put(line.lineReference(), line) != null) {
                errors.add(new FieldError("allocation_plan.lines", "line_reference must be unique"));
            }
        }
        if (plan.size() != request.items().size()) {
            errors.add(new FieldError("allocation_plan.lines", "must map one-to-one to items"));
        }
        for (CheckoutItem item : request.items()) {
            AllocationPlanLine line = plan.get(item.lineReference());
            if (line == null) {
                errors.add(new FieldError("allocation_plan.lines", "missing line_reference " + item.lineReference()));
            } else if (!line.skuId().equals(item.skuId()) || !line.quantity().equals(item.quantity())) {
                errors.add(new FieldError("allocation_plan.lines",
                        "sku_id/quantity mismatch for line_reference " + item.lineReference()));
            }
        }
        long total = request.pricing().totalAmount().amountMinor();
        long discount = request.pricing().totalDiscount().amountMinor();
        long finalAmount = request.pricing().finalAmount().amountMinor();
        if (total != gross) {
            errors.add(new FieldError("pricing.total_amount", "must equal sum(quantity * unit_price)"));
        }
        if (finalAmount != total - discount || discount > total) {
            errors.add(new FieldError("pricing.final_amount", "must equal total_amount - total_discount"));
        }
        long promotionDiscount = request.promotions().stream()
                .mapToLong(p -> p.discountAmount().amountMinor()).sum();
        if (promotionDiscount != discount) {
            errors.add(new FieldError("promotions", "discounts must sum to total_discount"));
        }
        boolean noPromotion = request.promotions().isEmpty();
        if (noPromotion != (request.promotionCampaignRevision() == 0)) {
            errors.add(new FieldError("promotion_campaign_revision", "must be 0 iff promotions is empty"));
        }
        if (!pricingHash(request).equals(request.pricingHash())) {
            errors.add(new FieldError("pricing_hash", "does not match the canonical pricing snapshot"));
        }
        if (!itemsHash(request).equals(request.itemsHash())) {
            errors.add(new FieldError("items_hash", "does not match the canonical items snapshot"));
        }
        return errors;
    }

    public static String pricingHash(CheckoutOrderSubmissionRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("items");
        sortedBySku(request.items()).forEach(item -> items.addObject()
                .put("sku_id", item.skuId())
                .put("unit_price_minor", item.unitPrice().amountMinor()));
        root.put("promotion_campaign_revision", request.promotionCampaignRevision());
        root.put("total_discount_minor", request.pricing().totalDiscount().amountMinor());
        return "sha256:" + CanonicalJson.digest(root);
    }

    public static String itemsHash(CheckoutOrderSubmissionRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("items");
        sortedBySku(request.items()).forEach(item -> items.addObject()
                .put("quantity", item.quantity())
                .put("sku_id", item.skuId()));
        return "sha256:" + CanonicalJson.digest(root);
    }

    public static long promotionDiscount(List<PromotionSnapshot> promotions) {
        return promotions.stream().mapToLong(p -> p.discountAmount().amountMinor()).sum();
    }

    private static List<CheckoutItem> sortedBySku(List<CheckoutItem> items) {
        List<CheckoutItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(i -> i.skuId().getBytes(StandardCharsets.UTF_8), CheckoutRules::compareBytes));
        return sorted;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}
