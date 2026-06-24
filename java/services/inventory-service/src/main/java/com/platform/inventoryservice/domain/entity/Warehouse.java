package com.platform.inventoryservice.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain entity cho bảng {@code warehouses}.
 * Trong Phase 14 chỉ cần lookup {@code is_active = true} — không có business logic phức tạp.
 * CRUD đầy đủ dành cho module admin (out of scope service này).
 */
@Getter
@Setter
@NoArgsConstructor
public class Warehouse {

    private Long id;
    private String name;
    private String address;
    private String cityCode;
    private String districtCode;
    private String wardCode;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public static Warehouse createActive(String name, String address, String cityCode) {
        Warehouse w = new Warehouse();
        w.name = name;
        w.address = address;
        w.cityCode = cityCode;
        w.active = true;
        return w;
    }

    public boolean canFulfill() {
        return active;
    }
}