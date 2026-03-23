package com.ecommerce.promotion.domain.enums;

/**
 * Trạng thái Sổ cái Coupon Usage (Ledger).
 * Theo thiết kế promotion_db_design.md - Section 1.6
 *
 * RESERVED:  Checkout đang giữ chỗ ngân sách, chưa thanh toán.
 * REDEEMED:  Đơn hàng thanh toán thành công, ngân sách đã xác nhận trừ.
 * CANCELLED: Đơn hàng bị hủy hoặc TTL Reservation 15 phút hết hạn.
 * REFUNDED:  Đơn hàng đã giao xong nhưng khách đổi trả hàng hoàn tiền.
 */
public enum UsageStatus {
    RESERVED,
    REDEEMED,
    CANCELLED,
    REFUNDED
}
