package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.paymentservice.application.port.out.RefundProviderOutcome;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.port.out.StripeWebhookEventParserPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class StripeWebhookEventParser implements StripeWebhookEventParserPort {
    private final ObjectMapper json;

    public StripeWebhookEventParser(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public StripeWebhookEvent parse(byte[] exactBody) throws InvalidStripeEventException {
        try {
            JsonNode envelope = json.readTree(exactBody);
            String eventId = requiredText(envelope, "id");
            String eventType = requiredText(envelope, "type");
            JsonNode created = envelope.get("created");
            if (created == null || !created.canConvertToLong()) {
                throw new InvalidStripeEventException("Stripe event created timestamp is missing");
            }
            JsonNode object = envelope.path("data").path("object");
            if (!object.isObject()) throw new InvalidStripeEventException("Stripe data.object is missing");

            String objectId = text(object, "id");
            String paymentIntentId = eventType.startsWith("payment_intent.")
                    ? objectId : objectId(object.get("payment_intent"));
            String chargeId = "charge.refunded".equals(eventType) ? objectId
                    : eventType.startsWith("charge.dispute.")
                    ? objectId(object.get("charge"))
                    : eventType.startsWith("refund.")
                    ? objectId(object.get("charge"))
                    : objectId(object.get("latest_charge"));
            String refundId = eventType.startsWith("refund.") ? objectId : null;
            String disputeId = eventType.startsWith("charge.dispute.") ? objectId : null;
            String metadataPaymentId = text(object.path("metadata"), "payment_transaction_id");
            JsonNode lastError = object.path("last_payment_error");
            JsonNode outcome = lastError.path("outcome");
            if (outcome.isMissingNode()) outcome = object.path("outcome");
            Long amount = "charge.refunded".equals(eventType)
                    && object.path("amount_refunded").canConvertToLong()
                    ? object.path("amount_refunded").longValue()
                    : object.path("amount").canConvertToLong()
                    ? object.path("amount").longValue() : null;
            Instant evidenceDueAt = object.path("evidence_details").path("due_by").canConvertToLong()
                    ? Instant.ofEpochSecond(object.path("evidence_details").path("due_by").longValue())
                    : null;

            ObjectNode sanitized = json.createObjectNode();
            put(sanitized, "id", objectId);
            put(sanitized, "object", text(object, "object"));
            put(sanitized, "status", text(object, "status"));
            put(sanitized, "payment_intent", paymentIntentId);
            put(sanitized, "latest_charge", chargeId);
            if (amount != null) sanitized.put("amount", amount);
            put(sanitized, "currency", text(object, "currency"));
            put(sanitized, "failure_reason", text(object, "failure_reason"));
            put(sanitized, "reason", text(object, "reason"));
            put(sanitized, "charge", chargeId);
            if (evidenceDueAt != null) sanitized.put("evidence_due_at", evidenceDueAt.toString());
            put(sanitized, "decline_code", text(lastError, "decline_code"));
            put(sanitized, "error_code", text(lastError, "code"));

            return new StripeWebhookEvent(eventId, eventType,
                    Instant.ofEpochSecond(created.longValue()), objectId,
                    paymentIntentId, chargeId, refundId, disputeId, metadataPaymentId,
                    amount,
                    upper(text(object, "currency")), text(object, "status"),
                    first(text(lastError, "decline_code"), text(lastError, "code")),
                    text(lastError, "message"),
                    text(outcome, "type"), failureReason(eventType, object),
                    text(object, "reason"), evidenceDueAt,
                    balanceTransactionId(eventType, object),
                    json.writeValueAsString(sanitized));
        } catch (InvalidStripeEventException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new InvalidStripeEventException("Stripe event payload cannot be parsed", invalid);
        }
    }

    private static String requiredText(JsonNode node, String field) throws InvalidStripeEventException {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new InvalidStripeEventException("Stripe event " + field + " is missing");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    /**
     * ADR-022: the balance transaction that books the money movement of this event. A
     * {@code charge.refunded} object is the charge, whose balance transaction is the original
     * capture, so it books nothing. A refund books {@code balance_transaction} on success and
     * {@code failure_balance_transaction} when Stripe gave the money back to the merchant.
     */
    private static String balanceTransactionId(String eventType, JsonNode object) {
        if ("charge.refunded".equals(eventType)) return null;
        if (eventType.startsWith("refund.")) {
            return switch (RefundProviderOutcome.of(text(object, "status"))) {
                case FAILED -> objectId(object.get("failure_balance_transaction"));
                case SUCCEEDED -> objectId(object.get("balance_transaction"));
                default -> null;
            };
        }
        return objectId(object.get("balance_transaction"));
    }

    private static String failureReason(String eventType, JsonNode object) {
        String reason = text(object, "failure_reason");
        return eventType.startsWith("refund.")
                ? RefundProviderOutcome.failureReason(text(object, "status"), reason) : reason;
    }

    private static String objectId(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        return text(value, "id");
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String first(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
}
