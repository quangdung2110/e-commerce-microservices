package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.ConcurrentFinancialMutationException;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.RefundPollingPort;
import com.platform.paymentservice.application.port.out.RefundProviderOutcome;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.RefundStateMachine;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.RefundTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RefundPollerService {
    private final RefundPollingPort refunds;
    private final ProviderCurrentFactPort provider;
    private final FinancialCommitPort commits;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final ObjectMapper json;
    private final Duration minimumAge;
    private final PaymentStateMachine paymentFsm = new PaymentStateMachine();
    private final RefundStateMachine refundFsm = new RefundStateMachine();

    public RefundPollerService(RefundPollingPort refunds, ProviderCurrentFactPort provider,
                               FinancialCommitPort commits, IdGeneratorPort ids, ClockPort clock,
                               ObjectMapper json, Duration minimumAge) {
        this.refunds = Objects.requireNonNull(refunds);
        this.provider = Objects.requireNonNull(provider);
        this.commits = Objects.requireNonNull(commits);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        if (minimumAge == null || minimumAge.isNegative() || minimumAge.isZero())
            throw new IllegalArgumentException("minimumAge must be positive");
        this.minimumAge = minimumAge;
    }

    public int poll(int limit) {
        Instant now = clock.now();
        List<RefundPollingPort.DueRefund> due = refunds.findDue(now.minus(minimumAge), limit);
        int finalized = 0;
        for (RefundPollingPort.DueRefund refund : due) {
            if (pollOne(refund, now)) finalized++;
        }
        return finalized;
    }

    private boolean pollOne(RefundPollingPort.DueRefund refund, Instant now) {
        StripeWebhookEvent source = new StripeWebhookEvent(
                "poll:" + refund.id() + ":" + now.toEpochMilli(), "refund.updated", now,
                refund.providerRefundId(), null, null, refund.providerRefundId(), null,
                refund.paymentId(), refund.amountMinor(), refund.currency(), null,
                null, null, null, null, null, null, null, "{}");
        ProviderCurrentFactPort.LookupResult lookup = provider.retrieveRefund(
                source, refund.providerRefundId());
        if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable) return false;
        if (lookup instanceof ProviderCurrentFactPort.NoTransition pending) {
            refunds.recordObservation(refund.id(), refund.version(), pending.providerStatus(), now);
            return false;
        }
        if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
            refunds.recordReview(refund, review.reasonCode(), review.evidenceJson(),
                    ids.nextReviewCaseId(), now);
            return false;
        }
        StripeWebhookEvent fact = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
        if (!refund.providerRefundId().equals(fact.providerRefundId())
                || fact.amountMinor() == null || fact.amountMinor() != refund.amountMinor()
                || !refund.currency().equals(fact.currency())) {
            refunds.recordReview(refund, "AMOUNT_MISMATCH", evidence(refund, fact, now),
                    ids.nextReviewCaseId(), now);
            return false;
        }
        RefundProviderOutcome outcome = RefundProviderOutcome.of(fact.providerStatus());
        if (outcome == RefundProviderOutcome.REQUIRES_ACTION) {
            // ADR-022: not a final fact while PROCESSING; a human decides.
            refunds.recordReview(refund, "RECONCILIATION_DRIFT", evidence(refund, fact, now),
                    ids.nextReviewCaseId(), now);
            return false;
        }
        boolean success = outcome == RefundProviderOutcome.SUCCEEDED;
        try {
            commitTerminal(refund, fact, success, now);
            return true;
        } catch (ConcurrentFinancialMutationException raced) {
            return false;
        }
    }

    private void commitTerminal(RefundPollingPort.DueRefund refund, StripeWebhookEvent fact,
                                boolean success, Instant now) {
        var refundTransition = refundFsm.transition(refund.status(), success
                ? RefundTrigger.REFUND_SUCCESS_CONFIRMED : RefundTrigger.REFUND_FAILED_CONFIRMED);
        var paymentTransition = paymentFsm.transition(refund.paymentStatus(), success
                ? PaymentTrigger.REFUND_SUCCESS_CONFIRMED : PaymentTrigger.REFUND_FAILED_CONFIRMED);
        long financialVersion = refund.financialStateVersion() + 1;
        var paymentChange = new CommitBatch.PaymentChange(refund.paymentId(), refund.paymentVersion(),
                refund.paymentStatus(), paymentTransition.to(), financialVersion,
                null, null, null, null, null, null, false, !success);
        var refundChange = new CommitBatch.RefundChange(refund.id(), refund.version(),
                RefundStatus.PROCESSING, refundTransition.to(), null, null,
                fact.providerRefundId(), success ? null : fact.failureReason(), null, now, null,
                fact.providerStatus(), now);
        String cause = "refund-poller:" + refund.id() + ":" + now;
        List<CommitBatch.HistoryRecord> history = List.of(
                new CommitBatch.HistoryRecord(refund.paymentId(), refund.id(), null, "REFUND",
                        refundTransition.transitionId(), "PROCESSING", refundTransition.to().name(),
                        financialVersion, "SCHEDULER", cause, null, fact.sanitizedObjectJson(), now),
                new CommitBatch.HistoryRecord(refund.paymentId(), null, null, "PAYMENT",
                        paymentTransition.transitionId(), refund.paymentStatus().name(),
                        paymentTransition.to().name(), financialVersion, "SCHEDULER", cause,
                        null, fact.sanitizedObjectJson(), now));
        CommitBatch.ReviewCaseRecord review = success ? null
                : new CommitBatch.ReviewCaseRecord(ids.nextReviewCaseId(), refund.paymentId(),
                refund.id(), "REFUND_FAILED_AFTER_ACCEPT", evidence(refund, fact, now),
                "SCHEDULER", now);
        commits.commit(new CommitBatch(paymentChange, refundChange, null, history,
                List.of(outbox(refund, success, financialVersion, fact.failureReason(), now)),
                review, null, null, success ? List.of(journal(refund, fact, now)) : List.of()));
    }

    private CommitBatch.OutboxRecord outbox(RefundPollingPort.DueRefund refund, boolean success,
                                             long version, String failure, Instant now) {
        var eventId = ids.nextEventId();
        String suffix = success ? "succeeded" : "failed";
        String type = "com.ecommerce.payment.refund." + suffix + ".v2";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(refund.orderId()));
        data.put("payment_transaction_id", refund.paymentId());
        data.put("payment_attempt_number", refund.paymentAttemptNumber());
        data.put("financial_state_version", version);
        data.put("occurred_at", now.toString());
        data.put("refund_transaction_id", refund.id());
        data.put("refund_amount", Map.of("amount_minor", refund.amountMinor(),
                "currency", refund.currency()));
        if (!success) data.put("error_code", normalize(failure));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0"); envelope.put("id", eventId.toString());
        envelope.put("source", "/services/payment");
        envelope.put("subject", "payments/" + refund.paymentId());
        envelope.put("type", type); envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/refund-" + suffix + ".schema.json");
        envelope.put("data", data);
        return new CommitBatch.OutboxRecord(eventId, refund.paymentId(), type,
                Long.toString(refund.orderId()), write(Map.of("event_id", eventId.toString(),
                "event_type", type)), write(envelope), now);
    }

    private CommitBatch.JournalRecord journal(RefundPollingPort.DueRefund refund,
                                               StripeWebhookEvent fact, Instant now) {
        List<Map<String, Object>> lines = List.of(
                Map.of("account_code", "CUSTOMER_REFUND_CLEARING", "direction", "DEBIT",
                        "amount_minor", refund.amountMinor(), "currency", refund.currency()),
                Map.of("account_code", "PROVIDER_RECEIVABLE", "direction", "CREDIT",
                        "amount_minor", refund.amountMinor(), "currency", refund.currency()));
        return new CommitBatch.JournalRecord(ids.nextFinancialJournalId(), refund.paymentId(),
                refund.id(), null, "REFUND", fact.providerBalanceTransactionId(), "REFUND",
                refund.providerRefundId(), null, refund.amountMinor(), refund.currency(), now,
                now, write(lines));
    }

    private String evidence(RefundPollingPort.DueRefund refund, StripeWebhookEvent fact, Instant now) {
        return write(List.of(Map.of("refund_transaction_id", refund.id(),
                "db_status", refund.status().name(), "provider_status",
                fact.providerStatus() == null ? "UNKNOWN" : fact.providerStatus(),
                "observed_at", now.toString())));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "PROVIDER_REJECTED"
                : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }
}
