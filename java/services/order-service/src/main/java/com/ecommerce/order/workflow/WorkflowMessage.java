package com.ecommerce.order.workflow;

import java.util.Map;

/**
 * Durable message delivered by the dispatcher through SignalWithStart. {@code messageId}
 * is stable: scoped {@code source:event:target:type} for facts, the operation ID for API
 * commands (ADR-015). Attributes are plain strings so workflow code stays deterministic.
 */
public record WorkflowMessage(String messageId, String type, Map<String, String> attributes) {

    public static final String PAYMENT_RESULT = "PAYMENT_RESULT";
    public static final String CANCEL_ORDER = "CANCEL_ORDER";
    public static final String NEXT_PAYMENT_ATTEMPT = "NEXT_PAYMENT_ATTEMPT";
    public static final String INSPECTION_COMPLETED = "INSPECTION_COMPLETED";
    public static final String REFUND_RESULT = "REFUND_RESULT";
    public static final String RESUME_REFUND = "RESUME_REFUND";
    public static final String NO_PAYOUT_DECIDED = "NO_PAYOUT_DECIDED";
    public static final String RESUME_POST_CAPTURE = "RESUME_POST_CAPTURE";

    public String attr(String name) {
        return attributes == null ? null : attributes.get(name);
    }
}
