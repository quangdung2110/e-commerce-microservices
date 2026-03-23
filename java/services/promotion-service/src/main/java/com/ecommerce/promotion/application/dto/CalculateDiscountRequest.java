package com.ecommerce.promotion.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request payload cho API /calculate-discount.
 * Theo thiết kế promotion_workflow.md - Luồng 3: Tính Giá Giỏ Hàng.
 * Order/Cart Service gửi toàn bộ payload giỏ hàng và thông tin User.
 */
public class CalculateDiscountRequest {

    private Long userId;
    private BigDecimal cartTotalAmount;
    private String paymentMethod;
    private String userTier;
    private List<CartItemDto> items;

    /**
     * Danh sách mã promotion mà user muốn áp dụng.
     * Nếu null/empty → hệ thống tự tìm eligible promotions.
     */
    private List<String> promotionCodes;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getCartTotalAmount() { return cartTotalAmount; }
    public void setCartTotalAmount(BigDecimal cartTotalAmount) { this.cartTotalAmount = cartTotalAmount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getUserTier() { return userTier; }
    public void setUserTier(String userTier) { this.userTier = userTier; }
    public List<CartItemDto> getItems() { return items; }
    public void setItems(List<CartItemDto> items) { this.items = items; }
    public List<String> getPromotionCodes() { return promotionCodes; }
    public void setPromotionCodes(List<String> promotionCodes) { this.promotionCodes = promotionCodes; }

    /**
     * Đại diện 1 item trong giỏ hàng.
     */
    public static class CartItemDto {
        private Long productId;
        private Long categoryId;
        private Long brandId;
        private Long sellerId;
        private BigDecimal price;
        private int quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public Long getBrandId() { return brandId; }
        public void setBrandId(Long brandId) { this.brandId = brandId; }
        public Long getSellerId() { return sellerId; }
        public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }
}
