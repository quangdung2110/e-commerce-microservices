package com.ecommerce.promotion.domain.enums;

/**
 * Loại phạm vi áp dụng Markdown Rule.
 * Theo thiết kế promotion_db_design.md - Section 2.1
 *
 * ALL chỉ áp dụng khi owner_type = SELLER (toàn bộ gian hàng của Shop).
 */
public enum ScopeType {
    PRODUCT,
    CATEGORY,
    BRAND,
    ALL
}
