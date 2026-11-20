package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    /**
     * Body của technical-cancellations (deprecated, legacy migration only; ADR-013/017/019). Khớp
     * {@code LegacyTechnicalCancellationRequest} của OpenAPI v1 ({@code additionalProperties: false}). Reader toàn cục
     * bỏ qua field thừa ({@code fail-on-unknown-properties: false}) và {@code @JsonIgnoreProperties} không ép được
     * ngược lại, nên field lạ bị từ chối bằng any-setter (→ 400). {@code migrationRecordId} phải bằng header
     * {@code X-Legacy-Migration-Proof}; cả hai field proof đi vào request hash và được lưu trên receipt.
     */
    public record LegacyTechnicalCancellationRequest(
            @JsonProperty("order_id") @NotBlank String orderId,
            @JsonProperty("promotion_id") @NotBlank String promotionId,
            @JsonProperty("expected_discount") @NotNull MoneyDto expectedDiscount,
            @JsonProperty("migration_record_id") @NotNull UUID migrationRecordId,
            @JsonProperty("pre_financial_watermark_evidence_sha256")
            @NotBlank @Pattern(regexp = "^[A-Fa-f0-9]{64}$") String preFinancialWatermarkEvidenceSha256) {

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown field " + name);
        }
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
