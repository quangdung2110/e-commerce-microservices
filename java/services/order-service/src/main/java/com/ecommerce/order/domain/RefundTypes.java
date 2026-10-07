package com.ecommerce.order.domain;

/** Refund case, scope finalization and reconciliation vocabularies (domain.md §5.5, data.md §3.6-3.8). */
public final class RefundTypes {

    private RefundTypes() {
    }

    public enum CaseStatus {
        INITIATED,
        WAITING_INVENTORY_SCOPE_INSPECTION,
        WAITING_INVENTORY_RELEASE,
        WAITING_PAYMENT_REFUND_RESULT,
        MANUAL_VERIFICATION_REQUIRED,
        SUCCEEDED,
        FAILED,
        EXTERNALLY_REMEDIATED,
        CLOSED_INCOMPLETE;

        public boolean resolved() {
            return this == SUCCEEDED || this == FAILED || this == EXTERNALLY_REMEDIATED || this == CLOSED_INCOMPLETE;
        }
    }

    public enum Mode {
        BUYER_RETURN_SELECTED_LINES,
        ADMIN_PRE_DISPATCH_FULL_ORDER
    }

    public enum FinalizationStatus {
        NOT_REQUIRED,
        NOT_REQUESTED,
        PENDING,
        RECOVERY_REQUIRED,
        CONFLICT,
        COMPLETED
    }

    public enum FinalizationOperation {
        CLOSE("CLOSED"),
        CLOSE_INCOMPLETE("CLOSED_INCOMPLETE"),
        CANCEL("CANCELLED");

        private final String expectedOutcome;

        FinalizationOperation(String expectedOutcome) {
            this.expectedOutcome = expectedOutcome;
        }

        public String expectedOutcome() {
            return expectedOutcome;
        }
    }

    public enum ObligationType {
        POST_CAPTURE_INVENTORY_REPAIR,
        POST_CAPTURE_PROMOTION_REPAIR,
        CAPTURE_AFTER_TERMINAL_NON_CAPTURE,
        REFUND_REVERSAL,
        CUSTOMER_PAYOUT_REMEDIATION,
        REFUND_SCOPE_FINALIZATION,
        RETURN_MANUAL_VERIFICATION,
        PAYMENT_DISPUTE;

        public boolean postCaptureRepair() {
            return this == POST_CAPTURE_INVENTORY_REPAIR || this == POST_CAPTURE_PROMOTION_REPAIR;
        }

        public boolean financialAnomaly() {
            return this == CAPTURE_AFTER_TERMINAL_NON_CAPTURE || this == REFUND_REVERSAL
                    || this == CUSTOMER_PAYOUT_REMEDIATION || this == PAYMENT_DISPUTE;
        }
    }

    public enum Resolution {
        RESUME_FORWARD_REPAIR,
        CLOSE_AS_MANUAL_FULFILLMENT,
        ESCALATE_TO_ACCOUNTING,
        CLOSE_RETURN_WITHOUT_REFUND,
        CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION
    }
}
