package com.ecommerce.checkout.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response bodies of the Checkout OpenAPI. Property names are snake_case by the global Jackson naming
 * strategy and nulls are serialized, because the contract marks several nullable fields as required.
 * The records mirror the schemas one to one; a contract test validates real responses against the
 * OpenAPI document.
 */
public final class PublicDtos {

    private PublicDtos() {
    }

    public record Money(long amountMinor, String currency) {

        public static Money vnd(long amountMinor) {
            return new Money(amountMinor, "VND");
        }
    }

    public record AddressSnapshot(String addressId, String recipientName, String recipientPhone, String addressLine,
                                  String provinceCode, String districtCode, String wardCode) {
    }

    public record PreviewItem(String lineReference, String productId, String skuId, String productName,
                              Map<String, String> variantAttributes, String imageUrl, int quantity,
                              Money listUnitPrice, Money unitPrice, Money lineTotal) {
    }

    public record PromotionPreview(String promotionId, long campaignRevision, Money discount) {
    }

    public record Pricing(Money totalAmount, Money totalDiscount, Money finalAmount) {
    }

    public record CheckoutPreview(String checkoutToken, Instant expiresAt, AddressSnapshot address,
                                  List<PreviewItem> items, PromotionPreview promotion, Pricing pricing) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = PercentageDiscountRule.class, name = "PERCENTAGE"),
            @JsonSubTypes.Type(value = FixedDiscountRule.class, name = "FIXED_VND")
    })
    public sealed interface DiscountRule permits PercentageDiscountRule, FixedDiscountRule {
    }

    public record PercentageDiscountRule(String type, int valueBasisPoints, Money maxDiscount)
            implements DiscountRule {
    }

    public record FixedDiscountRule(String type, Money value) implements DiscountRule {
    }

    public record VoucherOption(String promotionId, String source, String requiredAction,
                                long expectedCampaignRevision, String code, String name, String description,
                                DiscountRule discountRule, Money minOrderAmount, String validUntil, boolean eligible,
                                String reason, Money estimatedDiscount, Money estimatedFinalAmount,
                                boolean isApplied) {
    }

    public record VoucherOptions(String checkoutToken, String appliedPromotionId, List<VoucherOption> items,
                                 String evaluatedAt) {
    }

    public record PaymentClientData(int paymentAttemptNumber, String paymentTransactionId, boolean usable,
                                    String clientSecret, String publishableKey, String expiresAt) {
    }

    public record CheckoutSubmissionReady(String checkoutToken, String orderId, String orderStatus,
                                          PaymentClientData payment) {
    }

    public record CheckoutSubmissionPending(String checkoutToken, String state, String statusUrl) {
    }
}
