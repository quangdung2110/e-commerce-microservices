package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.VoucherSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO cho Customer promotions và Checkout queries. */
public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    public static final String PAYMENT_METHOD_STRIPE = "STRIPE";

    public record CheckoutItem(
            @NotNull UUID productId,
            @NotBlank @Size(max = 64) String skuId,
            @NotNull @Min(1) @Max(100) Integer quantity,
            @NotNull MoneyDto unitPrice,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 64) String> categoryIds) {
    }

    /**
     * Map sang rule facts cart.total_amount, cart.payment_method, cart.checkout_channel
     * và cart.items.category_id. payment_method bị khóa STRIPE (Checkout Phase 18); server
     * không tin payment method tùy ý từ client.
     */
    public record CheckoutContext(
            @NotNull MoneyDto subtotal,
            String paymentMethod,
            @NotBlank @Size(max = 64) String checkoutChannel,
            @NotEmpty @Size(max = 200) List<@Valid CheckoutItem> items,
            @Size(max = 100) Map<String, Object> facts) {
    }

    public record SuggestionRequest(@NotNull @Valid CheckoutContext context,
                                    @Min(1) @Max(50) Integer limit) {
    }

    public record DiscoveryRequest(@NotNull @Valid CheckoutContext context,
                                   @Min(1) @Max(50) Integer limit,
                                   Boolean includeIneligible) {
    }

    public record SaveAndEvaluateRequest(@NotNull @Valid CheckoutContext context) {
    }

    public record VoucherEvaluation(
            @JsonProperty("promotion_id") String promotionId,
            @JsonProperty("wallet_id") String walletId,
            VoucherSource source,
            @JsonProperty("distribution_mode") DistributionMode distributionMode,
            boolean eligible,
            EligibilityReason reason,
            MoneyDto discount,
            @JsonProperty("final_amount") MoneyDto finalAmount,
            @JsonProperty("rule_schema_version") int ruleSchemaVersion,
            @JsonProperty("campaign_revision") long campaignRevision,
            @JsonProperty("budget_guard") BudgetGuard budgetGuard) {
    }

    public record VoucherSuggestionResponse(List<VoucherEvaluation> items,
                                            @JsonProperty("evaluated_at") Instant evaluatedAt) {
    }

    public record SaveAndEvaluateResponse(WalletDtos.WalletVoucher wallet, VoucherEvaluation evaluation) {
    }

    public record ClaimVoucherRequest(@NotBlank String promotionId,
                                      @NotNull @Min(1) Long expectedCampaignRevision) {
    }

    public record ClaimVoucherResponse(
            @JsonProperty("claim_id") String claimId,
            @JsonProperty("acquired_at") Instant acquiredAt,
            @JsonProperty("campaign_revision_at_acquisition") long campaignRevisionAtAcquisition,
            WalletDtos.WalletVoucher wallet) {
    }

    public record ClaimPendingResponse(
            String code,
            String status,
            @JsonProperty("promotion_id") String promotionId,
            @JsonProperty("claim_id") String claimId,
            boolean retryable) {

        public static ClaimPendingResponse of(String promotionId, String claimId) {
            return new ClaimPendingResponse("CLAIM_PENDING", "PENDING", promotionId, claimId, true);
        }
    }

    public record DiscountCalculationRequest(
            @NotNull UUID userId,
            @NotBlank @Size(max = 64) String userTier,
            String promotionId,
            @NotNull @Valid CheckoutContext context) {
    }

    public record DiscountCalculationResult(
            @JsonProperty("promotion_id") String promotionId,
            boolean eligible,
            EligibilityReason reason,
            MoneyDto subtotal,
            MoneyDto discount,
            @JsonProperty("final_amount") MoneyDto finalAmount,
            @JsonProperty("rule_schema_version") Integer ruleSchemaVersion,
            @JsonProperty("campaign_revision") Long campaignRevision,
            @JsonProperty("budget_guard") BudgetGuard budgetGuard,
            @JsonProperty("evaluated_at") Instant evaluatedAt) {
    }
}
