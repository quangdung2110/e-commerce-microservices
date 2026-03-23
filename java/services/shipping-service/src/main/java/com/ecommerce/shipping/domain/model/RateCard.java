package com.ecommerce.shipping.domain.model;

import java.math.BigDecimal;

// Theo thiết kế shipping_db_design.md mục 1.2: rate card với fallback theo vùng.
public record RateCard(
        Long id,
        Integer providerId,
        String fromProvinceCode,
        String fromDistrictCode,
        String toProvinceCode,
        String toDistrictCode,
        String toWardCode,
        Integer baseWeight,
        BigDecimal baseFee,
        Integer stepWeight,
        BigDecimal stepFee,
        Integer estimatedDays
) {
}
