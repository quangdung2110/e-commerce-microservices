package com.ecommerce.order.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Wire shapes of contracts/http/order/internal/v1 Checkout submission. */
public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    public record VndMoney(
            @NotNull @Min(0) Long amountMinor,
            @NotNull @Pattern(regexp = "VND", message = "must be VND") String currency) {
    }

    public record AddressSnapshot(
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @Size(min = 5, max = 32) String recipientPhone,
            @NotBlank @Size(max = 500) String addressLine,
            @NotBlank @Size(max = 32) String provinceCode,
            @NotBlank @Size(max = 32) String districtCode,
            @NotBlank @Size(max = 32) String wardCode,
            @Size(max = 128) String provinceName,
            @Size(max = 128) String districtName,
            @Size(max = 128) String wardName) {
    }

    public record CheckoutItem(
            @NotBlank @Size(max = 64) String lineReference,
            @NotNull @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "must be a UUID") String productId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Min(1) @Max(100) Integer quantity,
            @NotNull @Valid VndMoney unitPrice,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 64) String> categoryIds,
            @NotBlank @Size(max = 255) String snapshotName,
            @Size(max = 2048) String snapshotImageUrl,
            @NotNull @Size(max = 50) Map<String, @Size(max = 255) String> snapshotAttributes,
            @Size(max = 255) String variantName) {
    }

    public record PricingSnapshot(
            @NotNull @Valid VndMoney totalAmount,
            @NotNull @Valid VndMoney totalDiscount,
            @NotNull @Valid VndMoney finalAmount) {
    }

    public record PromotionSnapshot(
            @NotBlank @Size(max = 128) String promotionId,
            @NotNull @Valid VndMoney discountAmount) {
    }

    public record AllocationPlanLine(
            @NotBlank @Size(max = 64) String lineReference,
            @NotBlank @Size(max = 64) String skuId,
            @NotBlank @Size(max = 128) String warehouseId,
            @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    public record AllocationPlan(
            @NotBlank @Size(max = 128) String allocationPlanId,
            @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull AllocationPlanLine> lines) {
    }

    public record CheckoutOrderSubmissionRequest(
            @NotNull @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "must be a UUID") String checkoutToken,
            @NotNull @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "must be a UUID") String userId,
            @NotNull @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String pricingHash,
            @NotNull @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String itemsHash,
            @NotNull @Min(0) Long promotionCampaignRevision,
            @NotNull Boolean userAckDegradedPricing,
            @NotNull @Valid AddressSnapshot deliveryAddressSnapshot,
            @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull CheckoutItem> items,
            @NotNull @Valid PricingSnapshot pricing,
            @NotNull @Size(max = 1) List<@Valid @NotNull PromotionSnapshot> promotions,
            @NotNull @Valid AllocationPlan allocationPlan) {
    }

    public record PaymentClientData(
            int paymentAttemptNumber,
            String paymentTransactionId,
            boolean usable,
            String clientSecret,
            String publishableKey,
            String expiresAt) {
    }

    public record CheckoutOrderReady(
            String checkoutToken,
            String orderId,
            String orderStatus,
            PaymentClientData payment) {
    }

    public record CheckoutOrderSubmissionStatus(
            String checkoutToken,
            String state,
            String orderId,
            PaymentClientData payment,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object failure,
            String updatedAt) {
    }
}
