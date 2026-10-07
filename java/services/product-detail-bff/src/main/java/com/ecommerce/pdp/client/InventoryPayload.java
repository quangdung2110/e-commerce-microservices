package com.ecommerce.pdp.client;

import com.ecommerce.pdp.resilience.ContractViolationException;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code BatchSkuAvailabilityResponse} của Inventory (contracts/http/inventory/read/v1).
 *
 * <p>Trùng {@code sku_id} làm cả batch vô dụng: không biết item nào đúng. Item thiếu hoặc
 * thừa thì không phải lỗi cả batch; composer xử lý theo từng SKU và ghi vi phạm mức item
 * (ADR-007).
 */
public record InventoryPayload(List<Item> items) {

    static final Set<String> STATUSES = Set.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN");

    public record Item(String skuId, String status, Long availabilityRevision, OffsetDateTime evaluatedAt) {
    }

    public InventoryPayload validated() {
        require(items != null && items.size() <= 500, "items must be a list of at most 500");
        Set<String> seen = new HashSet<>();
        for (Item item : items) {
            require(item != null, "item is null");
            require(item.skuId() != null && !item.skuId().isBlank() && item.skuId().length() <= 128,
                    "items.sku_id must be a non-empty string of at most 128");
            require(seen.add(item.skuId()), "duplicate sku_id " + item.skuId());
            require(STATUSES.contains(item.status()), "items.status is not a known availability status");
        }
        return this;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContractViolationException("Inventory: " + message);
        }
    }
}
