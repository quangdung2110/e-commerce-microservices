package com.platform.inventoryservice.domain.vo;

/**
 * Snapshot trạng thái stock hiện tại trong Redis hash {@code inventory:physic:{sku:wh}:stock}.
 * Read-only — application không mutate trực tiếp (chỉ qua Lua scripts).
 */
public record StockHash(long total, long reserved, long available, long updatedAtMs) {

    public StockHash {
        if (total < 0 || reserved < 0 || available < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        if (total != reserved + available) {
            throw new IllegalArgumentException(
                    "Invariant violated: total(" + total + ") != reserved(" + reserved
                            + ") + available(" + available + ")");
        }
    }

    public static StockHash empty() {
        return new StockHash(0L, 0L, 0L, 0L);
    }
}