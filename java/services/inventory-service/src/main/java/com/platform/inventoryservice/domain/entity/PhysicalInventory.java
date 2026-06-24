package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain entity cho bảng {@code physical_inventory}.
 * Aggregate duy nhất cho hot path — mọi mutation đi qua Lua script;
 * domain method {@code applyReserve/applyDeduct/applyRelease} giữ invariant tại Java layer
 * (dùng cho admin REST ở M12 hoặc unit test logic).
 */
@Getter
@Setter
@NoArgsConstructor
public class PhysicalInventory {

    private Long id;
    private SkuWarehouseKey key;
    private String productId;
    private int actualQuantity;
    private int reservedQuantity;
    private Instant updatedAt;

    /**
     * Constructor dùng cho cả create mới (restock lần đầu) và update.
     */
    public static PhysicalInventory forUpsert(SkuWarehouseKey key, String productId, int actualQtyToAdd) {
        PhysicalInventory p = new PhysicalInventory();
        p.key = key;
        p.productId = productId;
        p.actualQuantity = actualQtyToAdd;
        p.reservedQuantity = 0;
        return p;
    }

    public int availableQuantity() {
        return actualQuantity - reservedQuantity;
    }

    public void addActualQuantity(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be non-negative");
        }
        this.actualQuantity += delta;
    }

    public void applyReserve(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (reservedQuantity + qty > actualQuantity) {
            throw new IllegalStateException("Cannot reserve more than available");
        }
        this.reservedQuantity += qty;
    }

    public void applyDeduct(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (reservedQuantity < qty) {
            throw new IllegalStateException("Insufficient reserved for deduct");
        }
        this.reservedQuantity -= qty;
        this.actualQuantity -= qty;
    }

    public void applyRelease(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (reservedQuantity < qty) {
            throw new IllegalStateException("Cannot release more than reserved");
        }
        this.reservedQuantity -= qty;
    }
}