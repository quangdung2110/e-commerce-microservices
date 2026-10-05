package com.ecommerce.order.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Serializable activity results; plain values keep workflow code deterministic. */
public final class ActivityResults {

    private ActivityResults() {
    }

    /** Remote command outcome: ACCEPTED, REJECTED or UNKNOWN (+ error code). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outcome(String kind, String errorCode) {

        public static Outcome accepted() {
            return new Outcome("ACCEPTED", null);
        }

        public static Outcome rejected(String code) {
            return new Outcome("REJECTED", code);
        }

        public static Outcome unknown(String code) {
            return new Outcome("UNKNOWN", code);
        }

        @JsonIgnore
        public boolean isAccepted() {
            return "ACCEPTED".equals(kind);
        }

        @JsonIgnore
        public boolean isRejected() {
            return "REJECTED".equals(kind);
        }

        @JsonIgnore
        public boolean isUnknown() {
            return "UNKNOWN".equals(kind);
        }
    }

    /** Durable checkout state used to (re)enter the right phase. */
    public record Prepared(long orderId, String status, List<String> promotionIds, boolean deadlineLocked,
                           long deadlineEpochMillis) {
    }

    /** READY, CANCELLING (cancelled during preparation) or WINDOW_EXHAUSTED. */
    public record Ready(String state, long deadlineEpochMillis) {
    }

    /** Result of applying a payment fact or starting cancellation. */
    public record Applied(String action) {
        public static final String PAID = "PAID";
        public static final String ALREADY_PAID = "ALREADY_PAID";
        public static final String ATTEMPT_FAILED = "ATTEMPT_FAILED";
        public static final String CANCEL_STARTED = "CANCEL_STARTED";
        public static final String NON_CAPTURE = "NON_CAPTURE";
        public static final String CANCELLED = "CANCELLED";
        public static final String ANOMALY = "ANOMALY";
        public static final String IGNORED = "IGNORED";

        public boolean is(String value) {
            return value.equals(action);
        }
    }

    /** NON_CAPTURE, CAPTURED, PENDING or NO_PAYMENT. */
    public record CancelResult(String result) {
    }

    public record NextAttempt(boolean eligible, int attemptNumber, String returnUrl) {
    }

    /** Refund workflow entry state. */
    public record RefundState(String phase, long inspectionDeadlineEpochMillis) {
    }
}
