package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.port.out.RefundProviderOutcome;
import com.stripe.exception.StripeException;
import com.stripe.model.Dispute;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class StripeProviderCurrentFactAdapter implements ProviderCurrentFactPort {
    private static final String DISPUTE_WITHDRAWN = "charge.dispute.funds_withdrawn";
    private static final String DISPUTE_REINSTATED = "charge.dispute.funds_reinstated";

    private final StripeGateway gateway;
    private final ObjectMapper json;
    private final RequestOptions requestOptions;

    StripeProviderCurrentFactAdapter(StripeGateway gateway, ObjectMapper json,
                                     Duration connectTimeout, Duration readTimeout,
                                     int maxNetworkRetries) {
        this.gateway = Objects.requireNonNull(gateway);
        this.json = Objects.requireNonNull(json);
        if (maxNetworkRetries < 0)
            throw new IllegalArgumentException("maxNetworkRetries must be non-negative");
        this.requestOptions = RequestOptions.builder()
                .setConnectTimeout(exactMillis(connectTimeout, "connectTimeout"))
                .setReadTimeout(exactMillis(readTimeout, "readTimeout"))
                .setMaxNetworkRetries(maxNetworkRetries)
                .build();
    }

    @Override
    public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String paymentIntentId) {
        try {
            PaymentIntent intent = gateway.retrievePaymentIntent(paymentIntentId, requestOptions);
            String eventType = switch (normalized(intent.getStatus())) {
                case "processing" -> "payment_intent.processing";
                case "succeeded" -> "payment_intent.succeeded";
                case "canceled" -> "payment_intent.canceled";
                default -> null;
            };
            Map<String, Object> evidence = commonEvidence(intent.getId(), intent.getStatus());
            put(evidence, "provider_charge_id", intent.getLatestCharge());
            put(evidence, "amount_minor", intent.getAmount());
            put(evidence, "currency", upper(intent.getCurrency()));
            if (eventType == null)
                return new NoTransition("CURRENT_PAYMENT_INTENT_NON_FACT",
                        intent.getStatus(), write(evidence));
            return new CurrentFact(new StripeWebhookEvent(source.eventId(), eventType,
                    source.eventCreatedAt(), intent.getId(), intent.getId(), intent.getLatestCharge(),
                    null, null, source.metadataPaymentTransactionId(), intent.getAmount(),
                    upper(intent.getCurrency()), intent.getStatus(), null, null, null, null,
                    null, null, null, write(evidence)));
        } catch (StripeException failure) {
            return failure(failure);
        }
    }

    @Override
    public LookupResult retrieveRefund(StripeWebhookEvent source, String providerRefundId) {
        try {
            Refund refund = gateway.retrieveRefund(providerRefundId, requestOptions);
            // ADR-022: the Refund object is the source of truth. Every terminal or actionable
            // status becomes a fact; the caller classifies it via RefundProviderOutcome, never
            // via the synthetic event type.
            RefundProviderOutcome outcome = RefundProviderOutcome.of(refund.getStatus());
            String failureReason = RefundProviderOutcome.failureReason(
                    refund.getStatus(), refund.getFailureReason());
            // Refund success books refund.balance_transaction; a failed/canceled refund books
            // refund.failure_balance_transaction (the amount Stripe put back on the balance).
            String balanceTransaction = switch (outcome) {
                case SUCCEEDED -> refund.getBalanceTransaction();
                case FAILED -> refund.getFailureBalanceTransaction();
                default -> null;
            };
            String eventType = switch (outcome) {
                case SUCCEEDED, REQUIRES_ACTION -> "refund.updated";
                case FAILED -> "refund.failed";
                case NON_TERMINAL -> null;
            };
            Map<String, Object> evidence = commonEvidence(refund.getId(), refund.getStatus());
            put(evidence, "payment_intent", refund.getPaymentIntent());
            put(evidence, "provider_charge_id", refund.getCharge());
            put(evidence, "amount_minor", refund.getAmount());
            put(evidence, "currency", upper(refund.getCurrency()));
            put(evidence, "failure_reason", failureReason);
            put(evidence, "pending_reason", refund.getPendingReason());
            if (eventType == null)
                return new NoTransition("CURRENT_REFUND_NON_TERMINAL",
                        refund.getStatus(), write(evidence));
            return new CurrentFact(new StripeWebhookEvent(source.eventId(), eventType,
                    source.eventCreatedAt(), refund.getId(), refund.getPaymentIntent(),
                    refund.getCharge(), refund.getId(), null,
                    source.metadataPaymentTransactionId(), refund.getAmount(),
                    upper(refund.getCurrency()), refund.getStatus(), null, null, null,
                    failureReason, null, null, balanceTransaction, write(evidence)));
        } catch (StripeException failure) {
            return failure(failure);
        }
    }

    @Override
    public LookupResult retrieveDispute(StripeWebhookEvent source, String providerDisputeId) {
        try {
            Dispute dispute = gateway.retrieveDispute(providerDisputeId, requestOptions);
            Map<String, Object> evidence = commonEvidence(dispute.getId(), dispute.getStatus());
            put(evidence, "provider_charge_id", dispute.getCharge());
            put(evidence, "amount_minor", dispute.getAmount());
            put(evidence, "currency", upper(dispute.getCurrency()));
            put(evidence, "reason", dispute.getReason());

            if (DISPUTE_WITHDRAWN.equals(source.eventType())
                    || DISPUTE_REINSTATED.equals(source.eventType())) {
                return new ReviewRequired("DISPUTE_FUNDS_TIE_UNRESOLVED", write(List.of(evidence)));
            }
            String eventType = switch (normalized(dispute.getStatus())) {
                case "won", "lost", "warning_closed" -> "charge.dispute.closed";
                case "needs_response", "under_review", "warning_needs_response",
                        "warning_under_review" -> "charge.dispute.updated";
                default -> null;
            };
            if (eventType == null)
                return new NoTransition("CURRENT_DISPUTE_NON_FACT",
                        dispute.getStatus(), write(evidence));
            return new CurrentFact(new StripeWebhookEvent(source.eventId(), eventType,
                    source.eventCreatedAt(), dispute.getId(), source.paymentIntentId(),
                    dispute.getCharge(), null, dispute.getId(),
                    source.metadataPaymentTransactionId(), dispute.getAmount(),
                    upper(dispute.getCurrency()), dispute.getStatus(), null, null, null,
                    null, dispute.getReason(), source.evidenceDueAt(), null, write(evidence)));
        } catch (StripeException failure) {
            return failure(failure);
        }
    }

    private LookupResult failure(StripeException failure) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("error_code", failure.getCode() == null
                ? failure.getClass().getSimpleName().toUpperCase(Locale.ROOT) : failure.getCode());
        put(evidence, "request_id", failure.getRequestId());
        put(evidence, "http_status", failure.getStatusCode());
        Integer status = failure.getStatusCode();
        if (status != null && status >= 400 && status < 500)
            return new ReviewRequired("PROVIDER_RETRIEVE_REJECTED", write(List.of(evidence)));
        return new TemporarilyUnavailable("PROVIDER_RETRIEVE_UNAVAILABLE");
    }

    private static Map<String, Object> commonEvidence(String id, String status) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("provider_object_id", id);
        evidence.put("provider_status", status);
        evidence.put("resolution", "PROVIDER_RETRIEVE");
        return evidence;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize provider fact", impossible);
        }
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static int exactMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be a positive integer millisecond duration");
        return Math.toIntExact(duration.toMillis());
    }
}
