package com.ecommerce.checkout.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * {@code items_hash} and {@code pricing_hash} exactly as the Order OpenAPI defines them:
 * {@code sha256:} plus lowercase hex SHA-256 of the RFC 8785 form of the documented JSON, with the
 * items sorted by the UTF-8 bytes of {@code sku_id}. Checkout defines no formula of its own
 * (interfaces.md, "Mapping sang Order submission").
 */
public final class Hashes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Hashes() {
    }

    /** Sort key shared by the quotation, both hashes and Inventory: byte order of the UTF-8 sku_id. */
    public static final Comparator<String> SKU_ORDER =
            Comparator.comparing(sku -> sku.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned);

    /** A (sku, quantity) pair as Cart reports it and as the quotation stores it. */
    public record SkuQuantity(String skuId, int quantity) {
    }

    /** A (sku, price) pair at the price Checkout charges. */
    public record SkuPrice(String skuId, long unitPriceMinor) {
    }

    public static String itemsHash(List<SkuQuantity> items) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("items");
        sorted(items, SkuQuantity::skuId).forEach(item -> array.addObject()
                .put("quantity", item.quantity())
                .put("sku_id", item.skuId()));
        return "sha256:" + CanonicalJson.digest(root);
    }

    public static String pricingHash(List<SkuPrice> items, long promotionCampaignRevision, long totalDiscountMinor) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("items");
        sorted(items, SkuPrice::skuId).forEach(item -> array.addObject()
                .put("sku_id", item.skuId())
                .put("unit_price_minor", item.unitPriceMinor()));
        root.put("promotion_campaign_revision", promotionCampaignRevision);
        root.put("total_discount_minor", totalDiscountMinor);
        return "sha256:" + CanonicalJson.digest(root);
    }

    private static <T> List<T> sorted(List<T> items, java.util.function.Function<T, String> sku) {
        List<T> copy = new ArrayList<>(items);
        copy.sort(Comparator.comparing(sku, SKU_ORDER));
        return copy;
    }
}
