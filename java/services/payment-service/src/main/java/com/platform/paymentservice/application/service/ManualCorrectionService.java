package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ReviewCasePort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import com.platform.paymentservice.application.port.out.WebhookPaymentQueryPort;
import com.platform.paymentservice.domain.DisputeState;
import com.platform.paymentservice.domain.DisputeStateMachine;
import com.platform.paymentservice.domain.DisputeTransition;
import com.platform.paymentservice.domain.DisputeTrigger;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.PaymentTransition;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.RefundStateMachine;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.RefundTransition;
import com.platform.paymentservice.domain.RefundTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ManualCorrectionService {
    public enum Outcome { APPLIED, MISSING, CONFLICT, INVALID_TRANSITION,
        IN_PROGRESS, KEY_REUSED, FENCED }
    public record Result(Outcome outcome) {}

    public record Command(String caseId, String aggregateType, String aggregateId,
                          String trigger, long amountMinor, String currency,
                          String providerStatus, String providerObjectId,
                          String providerBalanceTransactionId, String providerResponseJson,
                          String actor, String approver, String correlationId,
                          String idempotencyKey, String errorCode, String reasonCode) {
        public Command(String caseId, String aggregateType, String aggregateId, String trigger,
                       long amountMinor, String currency, String providerStatus, String providerObjectId,
                       String providerBalanceTransactionId, String providerResponseJson, String actor,
                       String approver, String correlationId, String idempotencyKey) {
            this(caseId, aggregateType, aggregateId, trigger, amountMinor, currency, providerStatus,
                    providerObjectId, providerBalanceTransactionId, providerResponseJson, actor,
                    approver, correlationId, idempotencyKey, null, null);
        }
    }

    private final ReviewCasePort cases;
    private final WebhookPaymentQueryPort queries;
    private final FinancialCommitPort commits;
    private final IdempotencyExecutor idempotency;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final ObjectMapper json;
    private final Duration leaseTtl;
    private final Duration retention;
    private final PaymentStateMachine paymentFsm = new PaymentStateMachine();
    private final RefundStateMachine refundFsm = new RefundStateMachine();
    private final DisputeStateMachine disputeFsm = new DisputeStateMachine();

    public ManualCorrectionService(ReviewCasePort cases, WebhookPaymentQueryPort queries,
                                   FinancialCommitPort commits, IdempotencyExecutor idempotency,
                                   IdGeneratorPort ids, ClockPort clock, ObjectMapper json,
                                   Duration leaseTtl, Duration retention) {
        this.cases = Objects.requireNonNull(cases); this.queries = Objects.requireNonNull(queries);
        this.commits = Objects.requireNonNull(commits); this.idempotency = Objects.requireNonNull(idempotency);
        this.ids = Objects.requireNonNull(ids); this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json); this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.retention = positive(retention, "retention");
    }

    public Result correct(Command command, String owner) {
        validate(command);
        CanonicalCommand canonical = new CanonicalCommand(IdempotencyAction.REVIEW_RESOLVE,
                Map.of("caseId", command.caseId(), "operation", "correction"), body(command));
        var result = idempotency.execute(command.idempotencyKey(), IdempotencyAction.REVIEW_RESOLVE,
                canonical, owner, clock.now(), leaseTtl, clock.now().plus(retention),
                () -> apply(command));
        return switch (result.outcome()) {
            case IN_PROGRESS -> new Result(Outcome.IN_PROGRESS);
            case KEY_REUSED -> new Result(Outcome.KEY_REUSED);
            case FENCED -> new Result(Outcome.FENCED);
            case EXECUTED, REPLAYED -> new Result(switch (result.response().statusCode()) {
                case 200 -> Outcome.APPLIED; case 404 -> Outcome.MISSING;
                case 422 -> Outcome.INVALID_TRANSITION; default -> Outcome.CONFLICT;
            });
        };
    }

    private StoredHttpResponse apply(Command command) {
        ReviewCasePort.Case review = cases.find(command.caseId()).orElse(null);
        if (review == null) return response(command.caseId(), 404, "MISSING");
        if (!"IN_PROGRESS".equals(review.status()) || !command.actor().equals(review.assignedTo()))
            return response(command.caseId(), 409, "CONFLICT");
        if (cases.correctionApplied(command.caseId()))
            return response(command.caseId(), 200, "ALREADY_APPLIED");
        try {
            json.readTree(command.providerResponseJson());
            switch (command.aggregateType()) {
                case "PAYMENT" -> correctPayment(command);
                case "REFUND" -> correctRefund(command, review);
                case "DISPUTE" -> correctDispute(command);
                default -> throw new IllegalArgumentException("invalid aggregate type");
            }
            return response(command.caseId(), 200, "APPLIED");
        } catch (MissingAggregateException missing) {
            return response(command.caseId(), 404, "MISSING");
        } catch (com.platform.paymentservice.domain.InvalidTransitionException
                 | IllegalArgumentException invalid) {
            return response(command.caseId(), 422, "INVALID_TRANSITION");
        } catch (JsonProcessingException invalidJson) {
            return response(command.caseId(), 422, "INVALID_PROVIDER_EVIDENCE");
        }
    }

    private void correctPayment(Command c) {
        var p = queries.findById(c.aggregateId()).orElseThrow(MissingAggregateException::new);
        requireCasePayment(c.caseId(), p.id());
        requireAmount(c, p.amountMinor(), p.currency());
        PaymentTransition transition = paymentFsm.transition(p.status(), PaymentTrigger.valueOf(c.trigger()));
        requirePaymentEvidence(c, p, transition.to());
        Instant now = clock.now();
        long version = p.financialStateVersion() + (transition.fact().isPresent() ? 1 : 0);
        String providerIntent = c.providerObjectId() != null && c.providerObjectId().startsWith("pi_")
                ? c.providerObjectId() : null;
        String providerCharge = c.providerObjectId() != null && c.providerObjectId().startsWith("ch_")
                ? c.providerObjectId() : null;
        var change = new CommitBatch.PaymentChange(p.id(), p.version(), p.status(), transition.to(),
                version, null, null, providerIntent, providerCharge,
                transition.to() == PaymentStatus.SUCCESS ? now : null,
                nonCaptureTerminal(transition.to()) ? now : null, true, false,
                c.providerStatus(), now);
        var history = history(p.id(), null, null, "PAYMENT", p.status().name(),
                transition.to().name(), version, c, now);
        List<CommitBatch.OutboxRecord> outbox = transition.fact().isPresent()
                ? List.of(paymentOutbox(p, transition.to(), version, c, now)) : List.of();
        List<CommitBatch.JournalRecord> journals = transition.to() == PaymentStatus.SUCCESS
                ? List.of(journal(p.id(), null, null, "CAPTURE", c,
                "CHARGE", c.providerObjectId(), p.amountMinor(), p.currency(),
                "PROVIDER_RECEIVABLE", "CUSTOMER_PAYMENT_CLEARING", now)) : List.of();
        commits.commit(new CommitBatch(change, null, null, List.of(history), outbox,
                null, null, null, journals));
    }

    private void correctRefund(Command c, ReviewCasePort.Case review) {
        var r = queries.findRefundById(c.aggregateId()).orElseThrow(MissingAggregateException::new);
        requireCasePayment(c.caseId(), r.paymentId());
        if (review.refundId() != null && !review.refundId().equals(r.id()))
            throw new IllegalArgumentException("case/refund mismatch");
        requireAmount(c, r.amountMinor(), r.currency());
        RefundTrigger trigger = RefundTrigger.valueOf(c.trigger());
        RefundTransition rt = refundFsm.transition(r.status(), trigger);
        PaymentTransition pt = pairedPayment(r.paymentStatus(), trigger);
        Instant now = clock.now();
        long version = r.financialStateVersion() + 1;
        CommitBatch.PaymentChange payment = new CommitBatch.PaymentChange(r.paymentId(), r.paymentVersion(), r.paymentStatus(),
                pt == null ? r.paymentStatus() : pt.to(), version, null, null, null, null, null, null, false, false);
        var refund = new CommitBatch.RefundChange(r.id(), r.version(), r.status(), rt.to(),
                null, null, c.providerObjectId(),
                rt.to() == RefundStatus.FAILED ? c.providerStatus() : null,
                rt.to() == RefundStatus.PROCESSING ? now : null,
                rt.to() == RefundStatus.SUCCESS || rt.to() == RefundStatus.FAILED ? now : null,
                rt.to() == RefundStatus.REVERSED ? now : null, c.providerStatus(), now);
        List<CommitBatch.HistoryRecord> history = pt == null
                ? List.of(history(r.paymentId(), r.id(), null, "REFUND", r.status().name(),
                rt.to().name(), version, c, now))
                : List.of(history(r.paymentId(), r.id(), null, "REFUND", r.status().name(),
                rt.to().name(), version, c, now),
                history(r.paymentId(), null, null, "PAYMENT", r.paymentStatus().name(),
                        pt.to().name(), version, c, now));
        List<CommitBatch.JournalRecord> journals = switch (rt.to()) {
            case SUCCESS -> List.of(journal(r.paymentId(), r.id(), null, "REFUND", c,
                    "REFUND", c.providerObjectId(), r.amountMinor(), r.currency(),
                    "CUSTOMER_REFUND_CLEARING", "PROVIDER_RECEIVABLE", now));
            case REVERSED -> List.of(journal(r.paymentId(), r.id(), null, "REFUND_REVERSAL", c,
                    "REFUND_REVERSAL", c.providerObjectId() + ":reversal", r.amountMinor(), r.currency(),
                    "PROVIDER_RECEIVABLE", "CUSTOMER_REFUND_CLEARING", now));
            default -> List.of();
        };
        commits.commit(new CommitBatch(payment, refund, null, history,
                List.of(refundOutbox(r, rt.to(), version, c.providerStatus(), now)),
                null, null, null, journals));
    }

    private void correctDispute(Command c) {
        var d = queries.findDisputeById(c.aggregateId()).orElseThrow(MissingAggregateException::new);
        requireCasePayment(c.caseId(), d.paymentId());
        requireAmount(c, d.amountMinor(), d.currency());
        DisputeTransition transition = disputeFsm.transition(
                new DisputeState(d.status(), d.fundsState()), DisputeTrigger.valueOf(c.trigger()));
        Instant now = clock.now();
        boolean financial = transition.fact().isPresent();
        long version = d.financialStateVersion() + (financial ? 1 : 0);
        CommitBatch.PaymentChange payment = financial
                ? new CommitBatch.PaymentChange(d.paymentId(), d.paymentVersion(), d.paymentStatus(),
                d.paymentStatus(), version, null, null, null, null, null, null, false, false) : null;
        boolean closed = switch (transition.to().status()) {
            case WON, LOST, WARNING_CLOSED -> true; default -> false;
        };
        var dispute = new CommitBatch.DisputeChange(d.id(), d.version(), d.status(),
                transition.to().status(), transition.to().fundsState(), now,
                "manual:" + c.caseId(), d.evidenceDueAt(), closed ? now : null);
        var history = history(d.paymentId(), null, d.id(), "DISPUTE", d.status().name(),
                transition.to().status().name(), version, c, now);
        List<CommitBatch.OutboxRecord> outbox = financial
                ? List.of(disputeOutbox(d, transition.transitionId(), version, now)) : List.of();
        List<CommitBatch.JournalRecord> journals = switch (transition.transitionId()) {
            case "D3" -> List.of(journal(d.paymentId(), null, d.id(), "DISPUTE_WITHDRAWAL", c,
                    "DISPUTE_WITHDRAWAL", d.id(), d.amountMinor(), d.currency(),
                    "DISPUTE_HOLD_OR_LOSS", "PROVIDER_RECEIVABLE", now));
            case "D4" -> List.of(journal(d.paymentId(), null, d.id(), "DISPUTE_REINSTATEMENT", c,
                    "DISPUTE_REINSTATEMENT", d.id(), d.amountMinor(), d.currency(),
                    "PROVIDER_RECEIVABLE", "DISPUTE_HOLD_OR_LOSS", now));
            default -> List.of();
        };
        commits.commit(new CommitBatch(payment, null, dispute, List.of(history), outbox,
                null, null, null, journals));
    }

    private PaymentTransition pairedPayment(PaymentStatus status, RefundTrigger trigger) {
        return switch (trigger) {
            case REFUND_REQUEST_SENT -> null;
            case REFUND_SUCCESS_CONFIRMED -> paymentFsm.transition(status, PaymentTrigger.REFUND_SUCCESS_CONFIRMED);
            case REFUND_FAILED_CONFIRMED, REFUND_REQUEST_REJECTED ->
                    paymentFsm.transition(status, PaymentTrigger.REFUND_FAILED_CONFIRMED);
            case REFUND_REVERSED_CONFIRMED -> paymentFsm.transition(status, PaymentTrigger.REFUND_REVERSED_CONFIRMED);
        };
    }

    private CommitBatch.HistoryRecord history(String paymentId, String refundId, String disputeId,
                                               String type, String from, String to, long version,
                                               Command c, Instant now) {
        return new CommitBatch.HistoryRecord(paymentId, refundId, disputeId, type, "CORR",
                from, to, version, "MANUAL", c.caseId(), c.actor(),
                c.providerResponseJson(), now);
    }

    private CommitBatch.OutboxRecord paymentOutbox(WebhookPaymentQueryPort.PaymentSnapshot p,
                                                     PaymentStatus target, long version, Command c, Instant now) {
        String suffix = switch (target) {
            case SUCCESS -> "succeeded"; case FAILED -> "failed"; case EXPIRED -> "expired";
            case CANCELED -> "canceled"; default -> throw new IllegalArgumentException("no payment event");
        };
        Map<String, Object> data = common(p.orderId(), p.id(), p.attemptNumber(), version, now);
        data.put("amount", Map.of("amount_minor", p.amountMinor(), "currency", p.currency()));
        if (target == PaymentStatus.FAILED) data.put("error_code", c.errorCode());
        if (target == PaymentStatus.CANCELED) data.put("reason_code", c.reasonCode());
        return outbox(p.id(), p.orderId(), "com.ecommerce.payment.transaction." + suffix + ".v2",
                "payment-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord refundOutbox(WebhookPaymentQueryPort.RefundSnapshot r,
                                                    RefundStatus target, long version,
                                                    String providerStatus, Instant now) {
        String suffix = switch (target) {
            case PROCESSING -> "processing"; case SUCCESS -> "succeeded";
            case FAILED -> "failed"; case REVERSED -> "reversed";
            default -> throw new IllegalArgumentException("no refund event");
        };
        Map<String, Object> data = common(r.orderId(), r.paymentId(), r.attemptNumber(), version, now);
        data.put("refund_transaction_id", r.id());
        data.put("refund_amount", Map.of("amount_minor", r.amountMinor(), "currency", r.currency()));
        if (target == RefundStatus.FAILED) data.put("error_code", normalize(providerStatus));
        if (target == RefundStatus.REVERSED) data.put("reason_code", "REFUND_REVERSED_BY_PROVIDER");
        return outbox(r.paymentId(), r.orderId(), "com.ecommerce.payment.refund." + suffix + ".v2",
                "refund-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord disputeOutbox(WebhookPaymentQueryPort.DisputeSnapshot d,
                                                     String transitionId, long version, Instant now) {
        String suffix = switch (transitionId) {
            case "D3" -> "funds-withdrawn"; case "D4" -> "funds-reinstated";
            case "D5a" -> "won"; case "D5b" -> "lost";
            default -> throw new IllegalArgumentException("no dispute event");
        };
        Map<String, Object> data = common(d.orderId(), d.paymentId(), d.attemptNumber(), version, now);
        data.put("dispute_transaction_id", d.id());
        data.put("dispute_amount", Map.of("amount_minor", d.amountMinor(), "currency", d.currency()));
        return outbox(d.paymentId(), d.orderId(), "com.ecommerce.payment.dispute." + suffix + ".v2",
                "dispute-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord outbox(String paymentId, long orderId, String type,
                                             String schema, Map<String, Object> data, Instant now) {
        UUID id = ids.nextEventId();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0"); envelope.put("id", id.toString());
        envelope.put("source", "/services/payment"); envelope.put("subject", "payments/" + paymentId);
        envelope.put("type", type); envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/" + schema);
        envelope.put("data", data);
        return new CommitBatch.OutboxRecord(id, paymentId, type, Long.toString(orderId),
                write(Map.of("event_id", id.toString(), "event_type", type)), write(envelope), now);
    }

    private CommitBatch.JournalRecord journal(String paymentId, String refundId, String disputeId,
                                               String type, Command c, String sourceType, String sourceId,
                                               long amount, String currency, String debit,
                                               String credit, Instant now) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("provider source is required");
        List<Map<String, Object>> lines = List.of(
                Map.of("account_code", debit, "direction", "DEBIT", "amount_minor", amount, "currency", currency),
                Map.of("account_code", credit, "direction", "CREDIT", "amount_minor", amount, "currency", currency));
        return new CommitBatch.JournalRecord(ids.nextFinancialJournalId(), paymentId, refundId,
                disputeId, type, c.providerBalanceTransactionId(), sourceType, sourceId,
                null, amount, currency, now, now, write(lines));
    }

    private Map<String, Object> common(long orderId, String paymentId, int attempt,
                                       long version, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2); data.put("order_id", Long.toString(orderId));
        data.put("payment_transaction_id", paymentId); data.put("payment_attempt_number", attempt);
        data.put("financial_state_version", version); data.put("occurred_at", now.toString());
        return data;
    }

    private void requireCasePayment(String caseId, String paymentId) {
        if (!cases.find(caseId).map(ReviewCasePort.Case::paymentId).filter(paymentId::equals).isPresent())
            throw new IllegalArgumentException("case/payment mismatch");
    }

    private static void requireAmount(Command c, long expected, String currency) {
        if (c.amountMinor() != expected || !currency.equals(c.currency()))
            throw new IllegalArgumentException("amount correction is not an in-place mutation");
    }

    private StoredHttpResponse response(String caseId, int status, String outcome) {
        return new StoredHttpResponse(caseId, status,
                write(Map.of("case_id", caseId, "outcome", outcome)));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private Map<String, Object> body(Command c) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("aggregate_type", c.aggregateType()); value.put("aggregate_id", c.aggregateId());
        value.put("trigger", c.trigger()); value.put("amount_minor", c.amountMinor());
        value.put("currency", c.currency()); value.put("provider_status", c.providerStatus());
        value.put("provider_object_id", c.providerObjectId());
        value.put("provider_balance_transaction_id", c.providerBalanceTransactionId());
        value.put("actor", c.actor()); value.put("approver", c.approver());
        value.put("provider_response_json", evidence(c));
        value.put("error_code", c.errorCode()); value.put("reason_code", c.reasonCode());
        return value;
    }

    private static void validate(Command c) {
        Objects.requireNonNull(c);
        if (blank(c.caseId()) || blank(c.aggregateType()) || blank(c.aggregateId()) || blank(c.trigger())
                || c.amountMinor() <= 0 || !"VND".equals(c.currency()) || blank(c.providerStatus())
                || blank(c.providerResponseJson()) || blank(c.actor()) || blank(c.approver())
                || c.actor().equals(c.approver()) || blank(c.correlationId())
                || c.aggregateId().length() > 64 || c.providerStatus().length() > 64
                || c.providerObjectId() != null && c.providerObjectId().length() > 100
                || c.providerBalanceTransactionId() != null && c.providerBalanceTransactionId().length() > 120
                || c.providerResponseJson().length() > 10000 || c.actor().length() > 128
                || c.approver().length() > 128 || c.correlationId().length() > 128
                || c.idempotencyKey() == null || c.idempotencyKey().length() < 16
                || c.idempotencyKey().length() > 255)
            throw new IllegalArgumentException("invalid manual correction");
    }

    private com.fasterxml.jackson.databind.JsonNode evidence(Command c) {
        try {
            var node = json.readTree(c.providerResponseJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("evidence must be an object");
            rejectSensitive(node);
            return node;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("invalid evidence", invalid); }
    }

    private static void rejectSensitive(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            if (java.util.Set.of("client_secret", "card", "billing_details", "shipping", "email",
                    "phone", "address", "receipt_email").contains(field.getKey().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("sensitive provider evidence");
            rejectSensitive(field.getValue());
        });
        else if (node.isArray()) node.forEach(ManualCorrectionService::rejectSensitive);
    }

    private void requirePaymentEvidence(Command c, WebhookPaymentQueryPort.PaymentSnapshot p,
                                         PaymentStatus target) {
        var proof = evidence(c);
        if (nonCaptureTerminal(target)) {
            if (!"canceled".equals(c.providerStatus()) || !"canceled".equals(proof.path("status").asText())
                    || p.providerTransactionId() == null || !p.providerTransactionId().equals(c.providerObjectId())
                    || !p.providerTransactionId().equals(proof.path("id").asText()))
                throw new IllegalArgumentException("canceled intent evidence is required");
        } else if (target == PaymentStatus.SUCCESS) {
            if (!"succeeded".equals(c.providerStatus()) || !"succeeded".equals(proof.path("status").asText())
                    || p.providerTransactionId() == null || !p.providerTransactionId().equals(proof.path("id").asText())
                    || c.providerObjectId() == null || !c.providerObjectId().matches("ch_[A-Za-z0-9_]+")
                    || !c.providerObjectId().equals(proof.path("latest_charge").asText())
                    || p.providerChargeId() != null && !p.providerChargeId().equals(c.providerObjectId()))
                throw new IllegalArgumentException("succeeded intent and matching charge evidence are required");
        } else throw new IllegalArgumentException("manual payment target is unsupported");
        if (target == PaymentStatus.FAILED && (c.errorCode() == null
                || !c.errorCode().matches("[A-Z][A-Z0-9_]{0,63}")))
            throw new IllegalArgumentException("error_code is required");
        if (target == PaymentStatus.CANCELED && (c.reasonCode() == null
                || !java.util.Set.of("PAYMENT_TIMEOUT", "BUYER_CANCELLED", "ORDER_CANCELLED_PRE_CAPTURE", "PROVIDER_CANCELED")
                        .contains(c.reasonCode())))
            throw new IllegalArgumentException("reason_code is required");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) {
        return value == null ? "PROVIDER_REJECTED"
                : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }
    private static boolean nonCaptureTerminal(PaymentStatus status) {
        return status == PaymentStatus.FAILED || status == PaymentStatus.EXPIRED
                || status == PaymentStatus.CANCELED;
    }
    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static final class MissingAggregateException extends RuntimeException {}
}
