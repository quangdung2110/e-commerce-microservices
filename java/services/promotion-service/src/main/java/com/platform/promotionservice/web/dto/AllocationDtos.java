package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** DTO cho Order/Temporal allocation (TRY/CONFIRM/CANCEL/technical cancellation). */
public final class AllocationDtos {

    private AllocationDtos() {
    }

    public record ReserveRequest(
            @NotBlank String orderId,
            @NotNull UUID userId,
            @Size(max = 64) String userTier,
            @NotBlank String promotionId,
            @NotNull MoneyDto expectedDiscount,
            @NotNull @Valid CheckoutDtos.CheckoutContext context) {
    }

    public record ReservationResponse(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("promotion_id") String promotionId,
            String state,
            MoneyDto discount,
            @JsonProperty("reserved_at") Instant reservedAt,
            @JsonProperty("expires_at") Instant expiresAt) {
    }

    public record FinalAllocationRequest(
            @NotBlank String orderId,
            @NotBlank String promotionId,
            @NotNull MoneyDto expectedDiscount) {
    }

    public record ConfirmationResponse(
            String operation,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("promotion_id") String promotionId,
            MoneyDto discount,
            @JsonProperty("wallet_status") WalletStatus walletStatus,
            @JsonProperty("completed_at") Instant completedAt) {
    }

    public record TechnicalCancellationResponse(
            String operation,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("promotion_id") String promotionId,
            MoneyDto discount,
            @JsonProperty("wallet_status") WalletStatus walletStatus,
            @JsonProperty("completed_at") Instant completedAt) {
    }

    public record CancelReservationRequest(@NotBlank String orderId, @NotBlank String promotionId) {
    }

    public enum CancelOutcome { RELEASED, NO_RESERVATION, REPAIR_REQUIRED }

    public record CancelReservationResponse(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("promotion_id") String promotionId,
            CancelOutcome outcome) {
    }
}
