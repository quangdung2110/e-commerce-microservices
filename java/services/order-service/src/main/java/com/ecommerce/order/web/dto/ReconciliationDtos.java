package com.ecommerce.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** contracts/http/order/internal/v1 financial reconciliation decision request. */
public final class ReconciliationDtos {

    private static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private ReconciliationDtos() {
    }

    public record EvidenceReference(
            @NotNull @Pattern(regexp = "ORDER_CHECKPOINT|PAYMENT_RECONCILIATION|INVENTORY_COMMAND|PROMOTION_RECEIPT")
            String source,
            @NotBlank @Size(max = 255) String reference,
            @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String sha256) {
    }

    public record ReturnClosure(
            @NotBlank @Size(max = 128) String refundRequestId,
            @NotBlank @Size(max = 128) String refundScopeId,
            @NotNull @Pattern(regexp = "RETURN_WINDOW_EXPIRED|PACKAGE_LOST|CUSTOMER_WITHDREW") String reason) {
    }

    public record Money(@NotNull @Min(0) Long amountMinor,
                        @NotNull @Pattern(regexp = "VND", message = "must be VND") String currency) {
    }

    public record ExternalSettlement(
            @NotNull @Valid Money amount,
            @NotNull @Pattern(regexp = UUID) String customerUserId,
            @NotBlank String settledAt,
            @NotBlank @Size(max = 128) String settlementReference) {
    }

    public record DecisionRequest(
            @NotNull @Pattern(regexp = UUID) String caseId,
            @NotNull @Pattern(regexp = UUID) String obligationId,
            @NotNull @Min(1) Long expectedCaseRevision,
            @NotNull @Pattern(regexp = UUID) String decisionId,
            @NotNull @Pattern(regexp = "RESUME_FORWARD_REPAIR|CLOSE_AS_MANUAL_FULFILLMENT|ESCALATE_TO_ACCOUNTING|"
                    + "CLOSE_RETURN_WITHOUT_REFUND|CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION") String resolutionType,
            @NotBlank @Size(max = 100) String reasonCode,
            @Valid ReturnClosure returnClosure,
            @Size(max = 128) String accountingCaseReference,
            @Valid ExternalSettlement externalSettlement,
            @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull EvidenceReference> evidence) {
    }
}
