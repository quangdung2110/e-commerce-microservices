package com.ecommerce.promotion.domain.enums;

/**
 * Phân loại mã khuyến mãi theo cấu trúc Vận hành kinh doanh.
 * Theo thiết kế promotion_db_design.md - Section 1.2
 *
 * UNLIMITED_ALL: Mã do sàn tạo ra, gửi tới toàn user (VD: chúc mừng sinh nhật).
 * LIMITED_FLASH: Mã "Săn" do Sàn tung ra, có giới hạn ngân sách (Flash Sale).
 * SELLER_LIMITED: Mã do Seller tự tạo cho riêng shop mình.
 */
public enum PromotionType {
    UNLIMITED_ALL,
    LIMITED_FLASH,
    SELLER_LIMITED
}
