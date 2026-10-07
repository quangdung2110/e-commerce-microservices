package com.ecommerce.pdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload của Product, Inventory và Review đúng contract của từng provider, dựng bằng Map
 * để test sửa từng field khi cần làm hỏng contract.
 */
public final class Fixtures {

    public static final String PRODUCT_ID = "0190a3c4-0000-7000-8000-000000000001";
    public static final String SKU_BLACK_42 = "0190a3c4-0000-7000-8000-00000000000a";
    public static final String SKU_WHITE_42 = "0190a3c4-0000-7000-8000-00000000000b";
    public static final String SKU_BLACK_46 = "0190a3c4-0000-7000-8000-00000000000c";
    public static final String REVIEW_ID = "7d8f0f5e-4c43-4f7b-9c2a-2f6d1c3b9a10";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    /**
     * Product ACTIVE ba SKU: đen 42 có ảnh riêng, trắng 42 không ảnh (thumbnail null), đen
     * 46 dùng ảnh cấp Product. Có một field không có trong contract để kiểm tra tolerant
     * reader.
     */
    public static Map<String, Object> product() {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("product_id", PRODUCT_ID);
        product.put("name", "Đồng hồ thông minh");
        product.put("slug", "smart-watch");
        product.put("description", null);
        product.put("catalog_revision", 7);
        product.put("updated_at", "2026-09-20T10:00:00Z");
        product.put("category", Map.of(
                "category_id", "cat-watches",
                "name", "Đồng hồ",
                "slug", "watches",
                "path", List.of(
                        Map.of("category_id", "cat-electronics", "name", "Điện tử", "slug", "electronics"),
                        Map.of("category_id", "cat-watches", "name", "Đồng hồ", "slug", "watches"))));
        product.put("attributes", new ArrayList<>(List.of(
                attribute("material", "Chất liệu", "TEXT", "titanium", null),
                attribute("brand", "Thương hiệu", "SELECT", "garmin", "Garmin"))));
        product.put("variant_axes", new ArrayList<>(List.of(
                axis("size", "Kích thước", "NUMBER", List.of(option(42, null), option(46, null))),
                axis("color", "Màu sắc", "SELECT", List.of(option("black", "Đen"), option("white", "Trắng"))))));
        product.put("media", new ArrayList<>(List.of(
                media("m-hero", null, "IMAGE", "products/watch/hero shot.jpg", 0),
                media("m-black-front", SKU_BLACK_42, "IMAGE", "skus/black-42/front.jpg", 0),
                media("m-black-spin", SKU_BLACK_42, "VIDEO", "skus/black-42/spin.mp4", 1))));
        product.put("skus", new ArrayList<>(List.of(
                sku(SKU_BLACK_42, "W-BLACK-42", Map.of("size", 42, "color", "black"), "skus/black-42/front.jpg"),
                sku(SKU_WHITE_42, "W-WHITE-42", Map.of("size", 42, "color", "white"), null),
                sku(SKU_BLACK_46, "W-BLACK-46", Map.of("size", 46, "color", "black"),
                        "products/watch/hero shot.jpg"))));
        product.put("future_field", "ignored by the tolerant reader");
        return product;
    }

    public static Map<String, Object> attribute(String code, String name, String type, Object value, String label) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("code", code);
        attribute.put("name", name);
        attribute.put("value_type", type);
        attribute.put("value", value);
        attribute.put("value_label", label);
        return attribute;
    }

    public static Map<String, Object> axis(String code, String name, String type, List<Map<String, Object>> options) {
        return new LinkedHashMap<>(Map.of("code", code, "name", name, "value_type", type, "options", options));
    }

    public static Map<String, Object> option(Object value, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }

    public static Map<String, Object> media(String id, String skuId, String type, String storageKey, int sortOrder) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("media_id", id);
        media.put("sku_id", skuId);
        media.put("media_type", type);
        media.put("storage_key", storageKey);
        media.put("alt_text", null);
        media.put("sort_order", sortOrder);
        return media;
    }

    public static Map<String, Object> sku(String id, String code, Map<String, Object> variant, String thumbnail) {
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("sku_id", id);
        sku.put("sku_code", code);
        sku.put("base_price_vnd", 5_000_000);
        sku.put("selling_price_vnd", 4_500_000);
        sku.put("variant_attributes", variant);
        sku.put("thumbnail_storage_key", thumbnail);
        return sku;
    }

    /** Một item availability đúng contract Inventory; {@code UNKNOWN} không có revision/thời điểm. */
    public static Map<String, Object> availability(String skuId, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sku_id", skuId);
        item.put("status", status);
        boolean known = !status.equals("UNKNOWN");
        item.put("availability_revision", known ? 3 : null);
        item.put("evaluated_at", known ? "2026-09-25T08:00:00Z" : null);
        return item;
    }

    public static Map<String, Object> inventory(List<Map<String, Object>> items) {
        return Map.of("items", items);
    }

    public static Map<String, Object> review() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("review_id", REVIEW_ID);
        preview.put("rating", 5);
        preview.put("content", "Pin rất tốt");
        preview.put("author_name", "Người mua ẩn danh");
        preview.put("variant_name", "Đen 42");
        preview.put("has_media", false);
        preview.put("created_at", "2026-09-01T09:30:00Z");
        return Map.of(
                "product_id", PRODUCT_ID,
                "aggregate", Map.of("average_rating", 4.5, "total_reviews", 12, "total_with_media", 3),
                "preview", List.of(preview));
    }

    public static Map<String, Object> emptyReview() {
        return Map.of(
                "product_id", PRODUCT_ID,
                "aggregate", Map.of("average_rating", 0, "total_reviews", 0, "total_with_media", 0),
                "preview", List.of());
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
