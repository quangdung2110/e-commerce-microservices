package com.ecommerce.checkout.support;

import com.ecommerce.contracts.product.v1.Sku;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider payloads that match their OpenAPI contracts. The default cart is two SKUs priced so the
 * totals are easy to read: {@code sku-1} 2 x 100 000 and {@code sku-2} 1 x 50 000, total 250 000 VND.
 */
public final class Fixtures {

    public static final String ADDRESS_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    public static final String PRODUCT_1 = "11111111-1111-4111-8111-111111111111";
    public static final String PRODUCT_2 = "22222222-2222-4222-8222-222222222222";
    public static final String SKU_1 = "sku-1";
    public static final String SKU_2 = "sku-2";
    public static final String PROMOTION_ID = "promo-1";
    public static final String ORDER_ID = "10001";
    public static final long TOTAL = 250_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> money(long amount) {
        return map("amount_minor", amount, "currency", "VND");
    }

    // ------------------------------------------------------------------ Cart, User

    /** Cart reports sku-2 first on purpose: the quotation must sort by UTF-8 sku_id. */
    public static Map<String, Object> cart() {
        return cart(cartLine(PRODUCT_2, SKU_2, 1), cartLine(PRODUCT_1, SKU_1, 2));
    }

    @SafeVarargs
    public static Map<String, Object> cart(Map<String, Object>... lines) {
        return map("items", List.of(lines));
    }

    public static Map<String, Object> cartLine(String productId, String skuId, int quantity) {
        return map("product_id", productId, "sku_id", skuId, "quantity", quantity);
    }

    public static Map<String, Object> address() {
        return map("address_id", ADDRESS_ID, "recipient_name", "Nguyen Van A", "recipient_phone", "+84901234567",
                "address_line", "12 Le Loi", "province_code", "79", "district_code", "760", "ward_code", "26734");
    }

    // ------------------------------------------------------------------ Product (gRPC)

    public static Sku.Builder sku1() {
        return FakeProduct.sku(SKU_1, PRODUCT_1, "Ao thun", 100_000, 120_000)
                .putVariantAttributes("color", "black").putVariantAttributes("size", "M")
                .setImageUrl("products/ao thun/black.jpg").addCategoryIds("cat-root").addCategoryIds("cat-shirts");
    }

    /** No image: the quotation shows the placeholder, Order receives null. */
    public static Sku.Builder sku2() {
        return FakeProduct.sku(SKU_2, PRODUCT_2, "Non luoi trai", 50_000, 50_000).addCategoryIds("cat-root");
    }

    // ------------------------------------------------------------------ Inventory

    public static Map<String, Object> allocatable() {
        return map("outcome", "ALLOCATABLE", "allocation_plan_id", "plan-1", "generated_at", "2026-10-07T00:00:00Z",
                "lines", List.of(
                        plannedLine(SKU_1, 2, "wh-1"),
                        plannedLine(SKU_2, 1, "wh-2")));
    }

    public static Map<String, Object> plannedLine(String sku, int quantity, String warehouse) {
        return map("line_reference", sku, "sku_id", sku, "quantity", quantity, "warehouse_id", warehouse,
                "fulfillment_priority", 0, "selection_reason", "FULFILLMENT_PRIORITY");
    }

    public static Map<String, Object> rejectAll(String sku, int quantity) {
        return map("outcome", "REJECT_ALL", "generated_at", "2026-10-07T00:00:00Z", "rejected_lines", List.of(
                map("line_reference", sku, "sku_id", sku, "quantity", quantity, "reason", "NO_ELIGIBLE_WAREHOUSE")));
    }

    // ------------------------------------------------------------------ Promotion

    public static Map<String, Object> calculation(boolean eligible, String reason, long subtotal, long discount,
                                                  Long revision) {
        return map("promotion_id", PROMOTION_ID, "eligible", eligible, "reason", reason,
                "subtotal", money(subtotal), "discount", money(discount), "final_amount", money(subtotal - discount),
                "rule_schema_version", 1, "campaign_revision", revision, "budget_guard", "AVAILABLE",
                "evaluated_at", "2026-10-07T00:00:00Z");
    }

    public static Map<String, Object> eligibleCalculation() {
        return calculation(true, "ELIGIBLE", TOTAL, 25_000, 3L);
    }

    public static Map<String, Object> voucherOption(String id, String source, String mode, boolean eligible,
                                                    String reason, long discount, long revision,
                                                    Map<String, Object> displayDiscount) {
        return map(
                "evaluation", map("promotion_id", id, "source", source, "distribution_mode", mode,
                        "eligible", eligible, "reason", reason, "discount", money(discount),
                        "final_amount", money(TOTAL - discount), "rule_schema_version", 1,
                        "campaign_revision", revision, "budget_guard", "AVAILABLE"),
                "display", map("code", "CODE-" + id, "name", "Voucher " + id, "description", null,
                        "discount", displayDiscount, "min_order_amount", money(100_000),
                        "valid_until", "2026-12-31T00:00:00Z"));
    }

    public static Map<String, Object> percentage(int basisPoints, Long max) {
        return map("type", "PERCENTAGE", "value_basis_points", basisPoints,
                "max_discount", max == null ? null : money(max));
    }

    public static Map<String, Object> fixed(long value) {
        return map("type", "FIXED_VND", "value", money(value));
    }

    public static Map<String, Object> voucherOptions(List<Map<String, Object>> items) {
        return map("items", new ArrayList<>(items), "evaluated_at", "2026-10-07T00:00:00Z");
    }

    // ------------------------------------------------------------------ Order

    public static Map<String, Object> payment(boolean usable, String secret) {
        return map("payment_attempt_number", 1, "payment_transaction_id", "txn-1", "usable", usable,
                "client_secret", usable ? secret : null, "publishable_key", usable ? "pk_test_1" : null,
                "expires_at", usable ? "2026-10-07T01:00:00Z" : null);
    }

    public static Map<String, Object> orderReady(String token, boolean usable, String secret) {
        return map("checkout_token", token, "order_id", ORDER_ID, "order_status", "PENDING_PAYMENT",
                "payment", payment(usable, secret));
    }

    public static Map<String, Object> orderStatus(String token, String state, Map<String, Object> payment,
                                                  Map<String, Object> failure) {
        return map("checkout_token", token, "state", state, "order_id", payment == null ? null : ORDER_ID,
                "payment", payment, "failure", failure, "updated_at", "2026-10-07T00:00:00Z");
    }

    public static Map<String, Object> problem(int status, String code) {
        return map("type", "https://errors.ecommerce.local/" + code.toLowerCase().replace('_', '-'),
                "title", code, "status", status, "detail", code + " detail", "instance", "/internal/v1/x", "code", code);
    }

    public static Map<String, Object> recalculationProblem() {
        Map<String, Object> problem = problem(409, "PROMOTION_RECALCULATION_NEEDED");
        problem.put("action", "RECALL_PREVIEW");
        problem.put("failed_promotion_id", PROMOTION_ID);
        problem.put("failed_reason", "BUDGET_EXHAUSTED");
        problem.put("reserve_mode", "ALL_OR_NOTHING");
        problem.put("dropped_promotions", List.of(map("promotion_id", PROMOTION_ID, "reason", "BUDGET_EXHAUSTED")));
        return problem;
    }
}
