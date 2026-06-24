package com.platform.inventoryservice.domain.vo;

/**
 * Vị trí khách hàng dùng cho warehouse selection (same-province rule).
 * Phase 14 chỉ dùng {@code cityCode}; {@code districtCode} reserve cho future.
 */
public record CustomerLocation(String cityCode, String districtCode) {

    public CustomerLocation {
        if (cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("cityCode is required");
        }
    }
}