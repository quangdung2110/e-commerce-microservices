package com.ecommerce.order.messaging;

import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.service.ReconciliationLedger;
import com.ecommerce.order.service.WorkflowEnqueuer;
import com.ecommerce.order.store.InboxStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.PaymentAttemptStore;
import com.ecommerce.order.store.PaymentAttemptStore.AttemptRow;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * order-payment-consumer (WF-ORDER-005): validates each Payment v2 fact against the
 * persisted attempt/refund mapping and the per-transaction financial version, then
 * atomically writes the durable inbox and the workflow message (INV-ORD-018/027). It never
 * changes the master FSM and never calls Temporal. Disputes only touch reconciliation.
 */
@Component
public class PaymentIntake {

    public static final String SOURCE = "/services/payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentIntake.class);
    private static final Pattern TXN = Pattern.compile("^TXN_[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern RFD = Pattern.compile("^RFD_[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern DSP = Pattern.compile("^DSP_[0-9A-HJKMNP-TV-Z]{26}$");

    private static final Map<String, String> SCHEMAS = new LinkedHashMap<>();

    static {
        for (String[] t : new String[][]{
                {"transaction.succeeded", "payment-succeeded"}, {"transaction.failed", "payment-failed"},
                {"transaction.expired", "payment-expired"}, {"transaction.canceled", "payment-canceled"},
                {"refund.processing", "refund-processing"}, {"refund.succeeded", "refund-succeeded"},
                {"refund.failed", "refund-failed"}, {"refund.reversed", "refund-reversed"},
                {"dispute.opened", "dispute-opened"}, {"dispute.funds-withdrawn", "dispute-funds-withdrawn"},
                {"dispute.funds-reinstated", "dispute-funds-reinstated"}, {"dispute.won", "dispute-won"},
                {"dispute.lost", "dispute-lost"}}) {
            SCHEMAS.put("com.ecommerce.payment." + t[0] + ".v2",
                    "/contracts/events/payment/v2/schemas/" + t[1] + ".schema.json");
        }
    }

    private final InboxStore inbox;
    private final OrderStore orders;
    private final PaymentAttemptStore attempts;
    private final RefundStore refunds;
    private final ReconciliationLedger reconciliation;
    private final WorkflowEnqueuer enqueuer;
    private final TransactionTemplate tx;
    private final Json json;
    private final MeterRegistry meters;

    public PaymentIntake(InboxStore inbox, OrderStore orders, PaymentAttemptStore attempts, RefundStore refunds,
                         ReconciliationLedger reconciliation, WorkflowEnqueuer enqueuer, TransactionTemplate tx,
                         Json json, MeterRegistry meters) {
        this.inbox = inbox;
        this.orders = orders;
        this.attempts = attempts;
        this.refunds = refunds;
        this.reconciliation = reconciliation;
        this.enqueuer = enqueuer;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    /** Processes one Kafka record; returns normally only after the durable commit (then ACK). */
    public void handle(String key, String value) {
        CloudEvent event = CloudEvent.parse(json.mapper(), value);
        if (event == null || !SOURCE.equals(event.source()) || !SCHEMAS.containsKey(event.type())) {
            // Initiation facts and unknown/legacy types are ACKed and ignored by policy.
            meters.counter("order.consumer.ignored", "consumer", "payment").increment();
            return;
        }
        String problem = validate(key, event);
        Long orderId = Ids.parseCanonical(event.data() == null ? null : event.data().path("order_id").asText(null));
        String stream = event.data() == null ? "invalid" : event.data().path("payment_transaction_id").asText("invalid");
        long version = event.data() == null ? 0 : event.data().path("financial_state_version").asLong(0);
        String eventId = event.id() == null ? "missing-" + event.sha256() : event.id();
        tx.executeWithoutResult(s -> {
            boolean known = orderId != null && orders.lock(orderId).isPresent();
            String status = problem != null || !known ? "REJECTED" : "RECEIVED";
            InboxStore.InsertResult inserted = inbox.insert(SOURCE, eventId, event.type(), known ? orderId : null, stream, version, digest(event),
                    json.write(event.raw()), status);
            if (inserted != InboxStore.InsertResult.INSERTED) {
                meters.counter("order.consumer.duplicate", "consumer", "payment", "kind", inserted.name()).increment();
                return;
            }
            if (!"RECEIVED".equals(status)) {
                inbox.setStatus(SOURCE, eventId, "REJECTED", problem != null ? problem : "UNKNOWN_ORDER");
                log.warn("Rejected Payment fact {} {}: {}", event.type(), eventId, problem != null ? problem : "UNKNOWN_ORDER");
                return;
            }
            String result = route(orderId, event);
            inbox.setStatus(SOURCE, eventId, result.startsWith("REJECTED:") ? "REJECTED" : result,
                    result.startsWith("REJECTED:") ? result.substring(9) : null);
        });
    }

    /** Re-routes a parked fact (PENDING_IDENTITY_BIND) inside the caller's transaction. */
    public void reprocess(InboxStore.InboxRow row) {
        CloudEvent event = CloudEvent.parse(json.mapper(), row.payloadJson());
        if (event == null || row.orderId() == null) {
            inbox.setStatus(row.source(), row.eventId(), "REJECTED", "UNPARSEABLE_PARKED_EVENT");
            return;
        }
        orders.lock(row.orderId());
        String result = route(row.orderId(), event);
        inbox.setStatus(row.source(), row.eventId(), result.startsWith("REJECTED:") ? "REJECTED" : result,
                result.startsWith("REJECTED:") ? result.substring(9) : null);
    }

    private String validate(String key, CloudEvent event) {
        if (!"1.0".equals(event.specversion()) || !event.hasUuidId()) {
            return "INVALID_ENVELOPE";
        }
        if (!SCHEMAS.get(event.type()).equals(event.dataschema())) {
            return "DATASCHEMA_MISMATCH";
        }
        if (!event.dataIsObject() || event.data().path("schema_version").asInt() != 2) {
            return "UNSUPPORTED_SCHEMA_VERSION";
        }
        JsonNode data = event.data();
        String orderId = data.path("order_id").asText(null);
        if (Ids.parseCanonical(orderId) == null || (key != null && !key.equals(orderId))) {
            return "ORDER_KEY_MISMATCH";
        }
        if (!TXN.matcher(data.path("payment_transaction_id").asText("")).matches()
                || data.path("financial_state_version").asLong(0) < 1
                || data.path("payment_attempt_number").asInt(0) < 1) {
            return "INVALID_PAYMENT_IDENTITY";
        }
        if (event.type().contains(".refund.") && !RFD.matcher(data.path("refund_transaction_id").asText("")).matches()) {
            return "INVALID_REFUND_IDENTITY";
        }
        if (event.type().contains(".dispute.") && !DSP.matcher(data.path("dispute_transaction_id").asText("")).matches()) {
            return "INVALID_DISPUTE_IDENTITY";
        }
        return null;
    }

    /** Returns the inbox status (ROUTED/APPLIED/PENDING_IDENTITY_BIND) or "REJECTED:reason". */
    private String route(long orderId, CloudEvent event) {
        String family = event.type().split("\\.")[3];
        return switch (family) {
            case "transaction" -> routeTransaction(orderId, event);
            case "refund" -> routeRefund(orderId, event);
            case "dispute" -> routeDispute(orderId, event);
            default -> "REJECTED:UNSUPPORTED_TYPE";
        };
    }

    private String routeTransaction(long orderId, CloudEvent event) {
        OrderRow order = orders.find(orderId).orElseThrow();
        JsonNode data = event.data();
        String txn = data.path("payment_transaction_id").asText();
        int attemptNumber = data.path("payment_attempt_number").asInt();
        String result = switch (event.type().split("\\.")[4]) {
            case "succeeded" -> "SUCCESS";
            case "failed" -> "FAILED";
            case "expired" -> "EXPIRED";
            default -> "CANCELED";
        };
        Optional<AttemptRow> attempt = attempts.findByTransaction(txn);
        if (attempt.isEmpty()) {
            boolean bindPending = order.status().isPrePayment()
                    && attempts.find(orderId, attemptNumber).isEmpty();
            if (bindPending) {
                return "PENDING_IDENTITY_BIND";
            }
            if ("SUCCESS".equals(result)) {
                anomaly(order, txn, "UNKNOWN_TRANSACTION", event);
                return "APPLIED";
            }
            return "REJECTED:UNKNOWN_TRANSACTION";
        }
        AttemptRow row = attempt.get();
        long amount = data.path("amount").path("amount_minor").asLong(-1);
        String currency = data.path("amount").path("currency").asText();
        if (row.orderId() != orderId || row.attemptNumber() != attemptNumber || amount != row.amountMinor()
                || !"VND".equals(currency)) {
            if ("SUCCESS".equals(result)) {
                anomaly(order, txn, "ATTEMPT_MISMATCH", event);
                return "APPLIED";
            }
            return "REJECTED:ATTEMPT_MISMATCH";
        }
        if (!attempts.advanceVersion(txn, data.path("financial_state_version").asLong())) {
            return "REJECTED:STALE_FINANCIAL_VERSION";
        }
        if ("SUCCESS".equals(result) && (order.status() == OrderStatus.CANCELLED
                || (order.capturedTransactionId() != null && !order.capturedTransactionId().equals(txn)))) {
            anomaly(order, txn, order.status() == OrderStatus.CANCELLED ? "CAPTURE_AFTER_CANCELLED" : "SECOND_CAPTURE",
                    event);
            return "APPLIED";
        }
        if ("SUCCESS".equals(result) && txn.equals(order.capturedTransactionId())) {
            return "APPLIED";
        }
        if (order.status() == OrderStatus.CANCELLED || !order.status().isPrePayment() && !"SUCCESS".equals(result)) {
            // Evidence only: the checkout lane is already terminal or captured.
            return "APPLIED";
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("result", result);
        attrs.put("payment_transaction_id", txn);
        attrs.put("payment_attempt_number", Integer.toString(attemptNumber));
        attrs.put("amount_minor", Long.toString(amount));
        putIfPresent(attrs, "error_code", data.path("error_code").asText(null));
        putIfPresent(attrs, "reason_code", data.path("reason_code").asText(null));
        attrs.put("occurred_at", data.path("occurred_at").asText());
        WorkflowEnqueuer.Start target = enqueuer.checkout(order.checkoutToken());
        enqueuer.message(orderId, target, WorkflowEnqueuer.factMessageId(SOURCE, event.id(), target.workflowId(),
                WorkflowMessage.PAYMENT_RESULT), WorkflowMessage.PAYMENT_RESULT, attrs);
        return "ROUTED";
    }

    private String routeRefund(long orderId, CloudEvent event) {
        OrderRow order = orders.find(orderId).orElseThrow();
        JsonNode data = event.data();
        Optional<RefundStore.CaseRow> found = refunds.find(orderId);
        String txn = data.path("payment_transaction_id").asText();
        if (found.isEmpty() || order.capturedTransactionId() == null || !order.capturedTransactionId().equals(txn)) {
            return "REJECTED:NO_MATCHING_REFUND_CASE";
        }
        RefundStore.CaseRow refundCase = found.get();
        String refundTxn = data.path("refund_transaction_id").asText();
        if (refundCase.refundTransactionId() == null) {
            return refundCase.refundCommandId() != null ? "PENDING_IDENTITY_BIND" : "REJECTED:REFUND_NOT_INITIATED";
        }
        if (!refundCase.refundTransactionId().equals(refundTxn)) {
            return "REJECTED:SECOND_REFUND_TRANSACTION";
        }
        long amount = data.path("refund_amount").path("amount_minor").asLong(-1);
        if (amount != refundCase.amountLockedMinor() || !"VND".equals(data.path("refund_amount").path("currency").asText())) {
            return "REJECTED:REFUND_AMOUNT_MISMATCH";
        }
        if (!attempts.advanceVersion(txn, data.path("financial_state_version").asLong())) {
            return "REJECTED:STALE_FINANCIAL_VERSION";
        }
        String result = switch (event.type().split("\\.")[4]) {
            case "processing" -> "PROCESSING";
            case "succeeded" -> "SUCCEEDED";
            case "failed" -> "FAILED";
            default -> "REVERSED";
        };
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("result", result);
        attrs.put("refund_transaction_id", refundTxn);
        attrs.put("financial_state_version", data.path("financial_state_version").asText());
        attrs.put("occurred_at", data.path("occurred_at").asText());
        attrs.put("event_id", event.id());
        attrs.put("event_sha256", digest(event));
        putIfPresent(attrs, "error_code", data.path("error_code").asText(null));
        putIfPresent(attrs, "reason_code", data.path("reason_code").asText(null));
        WorkflowEnqueuer.Start target = enqueuer.refundTarget(orderId, refundCase.workflowId());
        enqueuer.message(orderId, target, WorkflowEnqueuer.factMessageId(SOURCE, event.id(), target.workflowId(),
                WorkflowMessage.REFUND_RESULT), WorkflowMessage.REFUND_RESULT, attrs);
        return "ROUTED";
    }

    private String routeDispute(long orderId, CloudEvent event) {
        OrderRow order = orders.find(orderId).orElseThrow();
        JsonNode data = event.data();
        String txn = data.path("payment_transaction_id").asText();
        if (order.capturedTransactionId() == null || !order.capturedTransactionId().equals(txn)) {
            anomaly(order, txn, "DISPUTE_ON_UNCAPTURED_TRANSACTION", event);
            return "APPLIED";
        }
        if (!attempts.advanceVersion(txn, data.path("financial_state_version").asLong())) {
            return "REJECTED:STALE_FINANCIAL_VERSION";
        }
        String dispute = data.path("dispute_transaction_id").asText();
        String kind = event.type().split("\\.")[4];
        reconciliation.open(orderId, ObligationType.PAYMENT_DISPUTE, dispute, "DISPUTE_" + kind.toUpperCase(),
                List.of(new ReconciliationLedger.Evidence("PAYMENT_RECONCILIATION",
                        "dispute/" + dispute + "/" + kind + "/v" + data.path("financial_state_version").asLong(),
                        digest(event), data.path("financial_state_version").asLong(), json.write(data))));
        meters.counter("order.payment.dispute", "kind", kind).increment();
        return "APPLIED";
    }

    private void anomaly(OrderRow order, String txn, String detail, CloudEvent event) {
        reconciliation.open(order.id(), ObligationType.CAPTURE_AFTER_TERMINAL_NON_CAPTURE, txn, detail,
                List.of(new ReconciliationLedger.Evidence("PAYMENT_RECONCILIATION",
                        "payment-event/" + event.id(), digest(event),
                        event.data().path("financial_state_version").asLong(), json.write(event.data()))));
        meters.counter("order.capture_after_terminal_non_capture").increment();
    }

    private static void putIfPresent(Map<String, String> attrs, String key, String value) {
        if (value != null) {
            attrs.put(key, value);
        }
    }

    static String digest(CloudEvent event) {
        try {
            return event.canonicalDigest();
        } catch (RuntimeException e) {
            return event.sha256();
        }
    }
}
