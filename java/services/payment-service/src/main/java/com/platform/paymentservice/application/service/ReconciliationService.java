package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import com.platform.paymentservice.application.port.out.RefundProviderOutcome;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.domain.InvalidTransitionException;
import com.platform.paymentservice.domain.DisputeState;
import com.platform.paymentservice.domain.DisputeStateMachine;
import com.platform.paymentservice.domain.DisputeStatus;
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

public final class ReconciliationService {
    public enum Outcome { COMPLETED, ALREADY_RUNNING, FAILED }

    private final ReconciliationPort persistence;
    private final ProviderCurrentFactPort provider;
    private final FinancialCommitPort commits;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final ObjectMapper json;
    private final PaymentStateMachine paymentFsm = new PaymentStateMachine();
    private final RefundStateMachine refundFsm = new RefundStateMachine();
    private final DisputeStateMachine disputeFsm = new DisputeStateMachine();

    /** Stripe can return a refunded amount to the merchant up to ~30 days later (ADR-022). */
    public static final Duration DEFAULT_REFUND_SUCCESS_LOOKBACK = Duration.ofDays(35);

    private final Duration refundSuccessLookback;

    public ReconciliationService(ReconciliationPort persistence, ProviderCurrentFactPort provider,
                                 FinancialCommitPort commits, IdGeneratorPort ids,
                                 ClockPort clock, ObjectMapper json) {
        this(persistence, provider, commits, ids, clock, json, DEFAULT_REFUND_SUCCESS_LOOKBACK);
    }

    public ReconciliationService(ReconciliationPort persistence, ProviderCurrentFactPort provider,
                                 FinancialCommitPort commits, IdGeneratorPort ids,
                                 ClockPort clock, ObjectMapper json,
                                 Duration refundSuccessLookback) {
        if (refundSuccessLookback == null || refundSuccessLookback.isNegative()
                || refundSuccessLookback.isZero())
            throw new IllegalArgumentException("refundSuccessLookback must be positive");
        this.refundSuccessLookback = refundSuccessLookback;
        this.persistence = Objects.requireNonNull(persistence);
        this.provider = Objects.requireNonNull(provider);
        this.commits = Objects.requireNonNull(commits);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
    }

    public Outcome runObjectState(Instant windowFrom, Instant windowTo,
                                  boolean frozen, int limit) {
        if (windowFrom == null || windowTo == null || !windowFrom.isBefore(windowTo))
            throw new IllegalArgumentException("invalid reconciliation window");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        Instant started = clock.now();
        var optional = persistence.start("OBJECT_STATE", windowFrom, windowTo, frozen, started);
        if (optional.isEmpty()) return Outcome.ALREADY_RUNNING;
        var run = optional.get();
        MutableCounts counts = new MutableCounts();
        try {
            for (var payment : persistence.findPayments(windowFrom, windowTo, limit)) {
                counts.scanned++;
                reconcilePayment(run, payment, counts);
            }
            int remaining = limit - counts.scanned;
            if (remaining > 0) {
                Instant successSince = windowTo.minus(refundSuccessLookback);
                for (var refund : persistence.findRefunds(windowFrom, windowTo, successSince, remaining)) {
                    counts.scanned++;
                    reconcileRefund(run, refund, counts);
                }
            }
            remaining = limit - counts.scanned;
            if (remaining > 0) {
                for (var dispute : persistence.findDisputes(windowFrom, windowTo, remaining)) {
                    counts.scanned++;
                    reconcileDispute(run, dispute, counts);
                }
            }
            persistence.complete(run.id(), null, counts.snapshot(), clock.now());
            return Outcome.COMPLETED;
        } catch (RuntimeException failure) {
            persistence.fail(run.id(), failure.getMessage(), clock.now());
            return Outcome.FAILED;
        }
    }

    private void reconcileDispute(ReconciliationPort.Run run,
                                  ReconciliationPort.DisputeCandidate dispute,
                                  MutableCounts counts) {
        Instant now = clock.now();
        StripeWebhookEvent source = disputeSource(dispute, now);
        var lookup = provider.retrieveDispute(source, dispute.providerDisputeId());
        if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
            throw new IllegalStateException(unavailable.errorCode());
        if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
            drift(dispute.paymentId(), null, review.evidenceJson(), counts, now);
            return;
        }
        if (lookup instanceof ProviderCurrentFactPort.NoTransition) return;
        StripeWebhookEvent fact = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
        if (!dispute.providerDisputeId().equals(fact.providerDisputeId())
                || !matches(fact, dispute.amountMinor(), dispute.currency())) {
            drift(dispute.paymentId(), null, evidence(dispute.status().name(), fact, now), counts, now);
            return;
        }
        DisputeTrigger trigger = disputeTrigger(fact);
        if (disputeMatches(dispute.status(), trigger)) return;
        if (run.autoCorrectionFrozen()) {
            drift(dispute.paymentId(), null, evidence(dispute.status().name(), fact, now), counts, now);
            return;
        }
        try {
            DisputeTransition transition = disputeFsm.transition(
                    new DisputeState(dispute.status(), dispute.fundsState()), trigger);
            boolean financial = transition.fact().isPresent();
            long version = dispute.financialStateVersion() + (financial ? 1 : 0);
            CommitBatch.PaymentChange paymentChange = financial
                    ? new CommitBatch.PaymentChange(dispute.paymentId(), dispute.paymentVersion(),
                    dispute.paymentStatus(), dispute.paymentStatus(), version,
                    null, null, null, null, null, null, false, false) : null;
            boolean closed = switch (transition.to().status()) {
                case WON, LOST, WARNING_CLOSED -> true;
                default -> false;
            };
            var disputeChange = new CommitBatch.DisputeChange(dispute.id(), dispute.version(),
                    dispute.status(), transition.to().status(), transition.to().fundsState(),
                    now, "reconciliation:" + run.id(), fact.evidenceDueAt(), closed ? now : null);
            var history = new CommitBatch.HistoryRecord(dispute.paymentId(), null, dispute.id(),
                    "DISPUTE", "CORR", dispute.status().name(), transition.to().status().name(),
                    version, "RECONCILIATION", Long.toString(run.id()),
                    "payment-reconciliation", fact.sanitizedObjectJson(), now);
            List<CommitBatch.OutboxRecord> outbox = financial
                    ? List.of(disputeOutbox(dispute, transition.transitionId(), version, now))
                    : List.of();
            commits.commit(new CommitBatch(paymentChange, null, disputeChange, List.of(history),
                    outbox, null, null, null, List.of()));
            counts.corrected++;
            counts.drift++;
        } catch (InvalidTransitionException invalid) {
            drift(dispute.paymentId(), null, evidence(dispute.status().name(), fact, now), counts, now);
        }
    }

    private void reconcilePayment(ReconciliationPort.Run run,
                                  ReconciliationPort.PaymentCandidate payment,
                                  MutableCounts counts) {
        Instant now = clock.now();
        StripeWebhookEvent source = source("reconcile-payment", payment.id(),
                payment.providerTransactionId(), null, payment.amountMinor(), payment.currency(), now);
        var lookup = provider.retrievePaymentIntent(source, payment.providerTransactionId());
        if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
            throw new IllegalStateException(unavailable.errorCode());
        if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
            drift(payment.id(), null, review.evidenceJson(), counts, now);
            return;
        }
        if (lookup instanceof ProviderCurrentFactPort.NoTransition noTransition) {
            persistence.recordPaymentObservation(payment.id(), payment.version(),
                    noTransition.providerStatus(), now);
            return;
        }
        StripeWebhookEvent fact = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
        if (!payment.providerTransactionId().equals(fact.paymentIntentId())
                || !matches(fact, payment.amountMinor(), payment.currency())) {
            drift(payment.id(), null, evidence(payment.status().name(), fact, now), counts, now);
            return;
        }
        if (paymentMatches(payment.status(), fact.eventType())) {
            persistence.recordPaymentObservation(payment.id(), payment.version(), fact.providerStatus(), now);
            return;
        }
        if (run.autoCorrectionFrozen()) {
            drift(payment.id(), null, evidence(payment.status().name(), fact, now), counts, now);
            return;
        }
        PaymentTrigger trigger = paymentTrigger(fact.eventType());
        if (trigger == null) {
            drift(payment.id(), null, evidence(payment.status().name(), fact, now), counts, now);
            return;
        }
        try {
            PaymentTransition transition = paymentFsm.transition(payment.status(), trigger);
            long version = payment.financialStateVersion() + (transition.fact().isPresent() ? 1 : 0);
            var change = new CommitBatch.PaymentChange(payment.id(), payment.version(), payment.status(),
                    transition.to(), version, null, null, fact.paymentIntentId(),
                    fact.providerChargeId(), transition.to() == PaymentStatus.SUCCESS ? now : null,
                    nonCaptureTerminal(transition.to()) ? now : null, true, false,
                    fact.providerStatus(), now);
            var history = new CommitBatch.HistoryRecord(payment.id(), null, null, "PAYMENT", "CORR",
                    payment.status().name(), transition.to().name(), version, "RECONCILIATION",
                    Long.toString(run.id()), "payment-reconciliation", fact.sanitizedObjectJson(), now);
            List<CommitBatch.OutboxRecord> outbox = transition.fact().isPresent()
                    ? List.of(paymentOutbox(payment, transition.to(), version, now)) : List.of();
            List<CommitBatch.JournalRecord> journals = transition.to() == PaymentStatus.SUCCESS
                    ? List.of(captureJournal(payment, fact, now)) : List.of();
            commits.commit(new CommitBatch(change, null, null, List.of(history), outbox,
                    null, null, null, journals));
            counts.corrected++;
            counts.drift++;
            if (transition.to() == PaymentStatus.SUCCESS) counts.captured += payment.amountMinor();
        } catch (InvalidTransitionException invalid) {
            drift(payment.id(), null, evidence(payment.status().name(), fact, now), counts, now);
        }
    }

    private void reconcileRefund(ReconciliationPort.Run run,
                                 ReconciliationPort.RefundCandidate refund,
                                 MutableCounts counts) {
        Instant now = clock.now();
        StripeWebhookEvent source = source("reconcile-refund", refund.paymentId(), null,
                refund.providerRefundId(), refund.amountMinor(), refund.currency(), now);
        var lookup = provider.retrieveRefund(source, refund.providerRefundId());
        if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
            throw new IllegalStateException(unavailable.errorCode());
        if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
            drift(refund.paymentId(), refund.id(), review.evidenceJson(), counts, now);
            return;
        }
        if (lookup instanceof ProviderCurrentFactPort.NoTransition noTransition) {
            // A refund we already booked as SUCCESS must not be pending at Stripe: look at it.
            if (refund.status() == RefundStatus.SUCCESS
                    && "pending".equalsIgnoreCase(noTransition.providerStatus())) {
                drift(refund.paymentId(), refund.id(), write(List.of(Map.of(
                        "refund_transaction_id", refund.id(),
                        "db_status", refund.status().name(),
                        "provider_status", "pending",
                        "observed_at", now.toString()))), counts, now);
                return;
            }
            persistence.recordRefundObservation(refund.id(), refund.version(),
                    noTransition.providerStatus(), now);
            return;
        }
        StripeWebhookEvent fact = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
        if (!refund.providerRefundId().equals(fact.providerRefundId())
                || !matches(fact, refund.amountMinor(), refund.currency())) {
            drift(refund.paymentId(), refund.id(), evidence(refund.status().name(), fact, now), counts, now);
            return;
        }
        // ADR-022: classify by Refund.status, not by the synthetic event type.
        RefundProviderOutcome outcome = RefundProviderOutcome.of(fact.providerStatus());
        if (outcome == RefundProviderOutcome.REQUIRES_ACTION
                && refund.status() != RefundStatus.SUCCESS) {
            // Not final while PROCESSING (a human decides); already FAILED/REVERSED needs nothing.
            if (refund.status() == RefundStatus.PROCESSING)
                drift(refund.paymentId(), refund.id(), evidence(refund.status().name(), fact, now),
                        counts, now);
            else persistence.recordRefundObservation(refund.id(), refund.version(),
                    fact.providerStatus(), now);
            return;
        }
        boolean successFact = outcome == RefundProviderOutcome.SUCCEEDED;
        // requires_action after SUCCESS means the bank returned the funds: same as failed.
        boolean failedFact = outcome == RefundProviderOutcome.FAILED
                || outcome == RefundProviderOutcome.REQUIRES_ACTION;
        if ((successFact && refund.status() == RefundStatus.SUCCESS)
                || (failedFact && (refund.status() == RefundStatus.FAILED
                || refund.status() == RefundStatus.REVERSED))) {
            persistence.recordRefundObservation(refund.id(), refund.version(), fact.providerStatus(), now);
            return;
        }
        if (run.autoCorrectionFrozen()) {
            drift(refund.paymentId(), refund.id(), evidence(refund.status().name(), fact, now), counts, now);
            return;
        }
        try {
            RefundTrigger refundTrigger;
            PaymentTrigger paymentTrigger;
            if (successFact) {
                refundTrigger = RefundTrigger.REFUND_SUCCESS_CONFIRMED;
                paymentTrigger = PaymentTrigger.REFUND_SUCCESS_CONFIRMED;
            } else if (refund.status() == RefundStatus.SUCCESS) {
                refundTrigger = RefundTrigger.REFUND_REVERSED_CONFIRMED;
                paymentTrigger = PaymentTrigger.REFUND_REVERSED_CONFIRMED;
            } else {
                refundTrigger = RefundTrigger.REFUND_FAILED_CONFIRMED;
                paymentTrigger = PaymentTrigger.REFUND_FAILED_CONFIRMED;
            }
            RefundTransition rt = refundFsm.transition(refund.status(), refundTrigger);
            PaymentTransition pt = paymentFsm.transition(refund.paymentStatus(), paymentTrigger);
            long version = refund.financialStateVersion() + 1;
            // A failed refund and a reversal both leave the customer unpaid: manual review.
            var paymentChange = new CommitBatch.PaymentChange(refund.paymentId(), refund.paymentVersion(),
                    refund.paymentStatus(), pt.to(), version, null, null, null, null,
                    null, null, false, !successFact);
            var refundChange = new CommitBatch.RefundChange(refund.id(), refund.version(), refund.status(),
                    rt.to(), null, null, fact.providerRefundId(), fact.failureReason(), null,
                    (successFact || failedFact) ? now : null,
                    rt.to() == RefundStatus.REVERSED ? now : null, fact.providerStatus(), now);
            List<CommitBatch.HistoryRecord> history = List.of(
                    new CommitBatch.HistoryRecord(refund.paymentId(), refund.id(), null, "REFUND", "CORR",
                            refund.status().name(), rt.to().name(), version, "RECONCILIATION",
                            Long.toString(run.id()), "payment-reconciliation", fact.sanitizedObjectJson(), now),
                    new CommitBatch.HistoryRecord(refund.paymentId(), null, null, "PAYMENT", "CORR",
                            refund.paymentStatus().name(), pt.to().name(), version, "RECONCILIATION",
                            Long.toString(run.id()), "payment-reconciliation", fact.sanitizedObjectJson(), now));
            boolean reversal = rt.to() == RefundStatus.REVERSED;
            var outbox = refundOutbox(refund, rt.to(), version, fact.failureReason(), now);
            List<CommitBatch.JournalRecord> journals = successFact
                    ? List.of(refundJournal(refund, fact, now, false))
                    : reversal ? List.of(refundJournal(refund, fact, now, true)) : List.of();
            CommitBatch.ReviewCaseRecord review = !successFact
                    ? new CommitBatch.ReviewCaseRecord(ids.nextReviewCaseId(), refund.paymentId(),
                    refund.id(), reversal ? "REFUND_REVERSED" : "REFUND_FAILED_AFTER_ACCEPT",
                    evidence(refund.status().name(), fact, now), "RECONCILIATION", now) : null;
            commits.commit(new CommitBatch(paymentChange, refundChange, null, history,
                    List.of(outbox), review, null, null, journals));
            counts.corrected++;
            counts.drift++;
            if (successFact) counts.refunded += refund.amountMinor();
            if (review != null) counts.reviews++;
        } catch (InvalidTransitionException invalid) {
            drift(refund.paymentId(), refund.id(), evidence(refund.status().name(), fact, now), counts, now);
        }
    }

    private void drift(String paymentId, String refundId, String evidence,
                       MutableCounts counts, Instant now) {
        persistence.openDrift(paymentId, refundId, ids.nextReviewCaseId(), evidence, now);
        counts.drift++;
        counts.reviews++;
    }

    private CommitBatch.OutboxRecord paymentOutbox(ReconciliationPort.PaymentCandidate payment,
                                                     PaymentStatus target, long version, Instant now) {
        String suffix = switch (target) {
            case SUCCESS -> "succeeded";
            case CANCELED -> "canceled";
            case EXPIRED -> "expired";
            case FAILED -> "failed";
            default -> throw new IllegalArgumentException("target has no financial event");
        };
        String type = "com.ecommerce.payment.transaction." + suffix + ".v2";
        var eventId = ids.nextEventId();
        Map<String, Object> data = common(payment.orderId(), payment.id(),
                payment.paymentAttemptNumber(), version, now);
        data.put("amount", Map.of("amount_minor", payment.amountMinor(), "currency", payment.currency()));
        return outbox(eventId, payment.id(), payment.orderId(), type,
                "payment-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord refundOutbox(ReconciliationPort.RefundCandidate refund,
                                                    RefundStatus target, long version,
                                                    String failure, Instant now) {
        String suffix = switch (target) {
            case SUCCESS -> "succeeded";
            case FAILED -> "failed";
            case REVERSED -> "reversed";
            default -> throw new IllegalArgumentException("target has no terminal event");
        };
        String type = "com.ecommerce.payment.refund." + suffix + ".v2";
        var eventId = ids.nextEventId();
        Map<String, Object> data = common(refund.orderId(), refund.paymentId(),
                refund.paymentAttemptNumber(), version, now);
        data.put("refund_transaction_id", refund.id());
        data.put("refund_amount", Map.of("amount_minor", refund.amountMinor(), "currency", refund.currency()));
        if (target == RefundStatus.FAILED) data.put("error_code", normalize(failure));
        return outbox(eventId, refund.paymentId(), refund.orderId(), type,
                "refund-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord disputeOutbox(ReconciliationPort.DisputeCandidate dispute,
                                                     String transitionId, long version, Instant now) {
        String suffix = switch (transitionId) {
            case "D5a" -> "won";
            case "D5b" -> "lost";
            default -> throw new IllegalArgumentException("transition has no dispute event");
        };
        String type = "com.ecommerce.payment.dispute." + suffix + ".v2";
        var eventId = ids.nextEventId();
        Map<String, Object> data = common(dispute.orderId(), dispute.paymentId(),
                dispute.paymentAttemptNumber(), version, now);
        data.put("dispute_transaction_id", dispute.id());
        data.put("dispute_amount", Map.of(
                "amount_minor", dispute.amountMinor(), "currency", dispute.currency()));
        return outbox(eventId, dispute.paymentId(), dispute.orderId(), type,
                "dispute-" + suffix + ".schema.json", data, now);
    }

    private CommitBatch.OutboxRecord outbox(java.util.UUID eventId, String paymentId, long orderId,
                                             String type, String schema, Map<String, Object> data,
                                             Instant now) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0"); envelope.put("id", eventId.toString());
        envelope.put("source", "/services/payment"); envelope.put("subject", "payments/" + paymentId);
        envelope.put("type", type); envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/" + schema);
        envelope.put("data", data);
        return new CommitBatch.OutboxRecord(eventId, paymentId, type, Long.toString(orderId),
                write(Map.of("event_id", eventId.toString(), "event_type", type)), write(envelope), now);
    }

    private CommitBatch.JournalRecord captureJournal(ReconciliationPort.PaymentCandidate payment,
                                                      StripeWebhookEvent fact, Instant now) {
        return journal(payment.id(), null, "CAPTURE", fact.providerBalanceTransactionId(),
                "CHARGE", fact.providerChargeId() == null ? fact.paymentIntentId() : fact.providerChargeId(),
                payment.amountMinor(), payment.currency(), "PROVIDER_RECEIVABLE", "DEBIT",
                "CUSTOMER_PAYMENT_CLEARING", "CREDIT", now);
    }

    private CommitBatch.JournalRecord refundJournal(ReconciliationPort.RefundCandidate refund,
                                                     StripeWebhookEvent fact, Instant now,
                                                     boolean reversal) {
        return journal(refund.paymentId(), refund.id(), reversal ? "REFUND_REVERSAL" : "REFUND",
                fact.providerBalanceTransactionId(), reversal ? "REFUND_REVERSAL" : "REFUND",
                refund.providerRefundId() + (reversal ? ":reversal" : ""), refund.amountMinor(),
                refund.currency(), reversal ? "PROVIDER_RECEIVABLE" : "CUSTOMER_REFUND_CLEARING",
                "DEBIT", reversal ? "CUSTOMER_REFUND_CLEARING" : "PROVIDER_RECEIVABLE",
                "CREDIT", now);
    }

    private CommitBatch.JournalRecord journal(String paymentId, String refundId, String type,
                                               String balanceId, String sourceType, String sourceId,
                                               long amount, String currency, String debitAccount,
                                               String debit, String creditAccount, String credit, Instant now) {
        List<Map<String, Object>> lines = List.of(
                Map.of("account_code", debitAccount, "direction", debit,
                        "amount_minor", amount, "currency", currency),
                Map.of("account_code", creditAccount, "direction", credit,
                        "amount_minor", amount, "currency", currency));
        return new CommitBatch.JournalRecord(ids.nextFinancialJournalId(), paymentId, refundId,
                null, type, balanceId, sourceType, sourceId, null, amount, currency,
                now, now, write(lines));
    }

    private static StripeWebhookEvent source(String prefix, String paymentId,
                                              String intentId, String refundId,
                                              long amount, String currency, Instant now) {
        return new StripeWebhookEvent(prefix + ":" + paymentId + ":" + now.toEpochMilli(), prefix,
                now, intentId == null ? refundId : intentId, intentId, null, refundId, null,
                paymentId, amount, currency, null, null, null, null, null, null,
                null, null, "{}");
    }

    private static StripeWebhookEvent disputeSource(ReconciliationPort.DisputeCandidate dispute,
                                                      Instant now) {
        String prefix = "reconcile-dispute";
        return new StripeWebhookEvent(prefix + ":" + dispute.id() + ":" + now.toEpochMilli(),
                prefix, now, dispute.providerDisputeId(), null, null, null,
                dispute.providerDisputeId(), dispute.paymentId(), dispute.amountMinor(),
                dispute.currency(), dispute.status().name().toLowerCase(java.util.Locale.ROOT),
                null, null, null, null, dispute.reasonCode(), dispute.evidenceDueAt(),
                null, "{}");
    }

    private static boolean matches(StripeWebhookEvent fact, long amount, String currency) {
        return fact.amountMinor() != null && fact.amountMinor() == amount
                && currency.equals(fact.currency());
    }

    private static boolean paymentMatches(PaymentStatus status, String eventType) {
        return ("payment_intent.processing".equals(eventType) && status == PaymentStatus.PROCESSING)
                || ("payment_intent.succeeded".equals(eventType)
                && (status == PaymentStatus.SUCCESS || status == PaymentStatus.REFUNDING
                || status == PaymentStatus.REFUNDED))
                || ("payment_intent.canceled".equals(eventType)
                && (status == PaymentStatus.CANCELED || status == PaymentStatus.EXPIRED));
    }

    private static PaymentTrigger paymentTrigger(String eventType) {
        return switch (eventType) {
            case "payment_intent.processing" -> PaymentTrigger.PROVIDER_PROCESSING;
            case "payment_intent.succeeded" -> PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED;
            case "payment_intent.canceled" -> PaymentTrigger.PAYMENT_CANCELED;
            default -> null;
        };
    }

    private static DisputeTrigger disputeTrigger(StripeWebhookEvent fact) {
        if ("charge.dispute.updated".equals(fact.eventType()))
            return DisputeTrigger.DISPUTE_UPDATED;
        if (!"charge.dispute.closed".equals(fact.eventType()))
            throw new IllegalArgumentException("unsupported dispute provider fact");
        return switch (fact.providerStatus() == null ? ""
                : fact.providerStatus().toLowerCase(java.util.Locale.ROOT)) {
            case "won" -> DisputeTrigger.DISPUTE_CLOSED_WON;
            case "lost" -> DisputeTrigger.DISPUTE_CLOSED_LOST;
            case "warning_closed" -> DisputeTrigger.WARNING_CLOSED;
            default -> throw new IllegalArgumentException("unsupported closed dispute status");
        };
    }

    private static boolean disputeMatches(DisputeStatus status, DisputeTrigger trigger) {
        return switch (trigger) {
            case DISPUTE_UPDATED -> status == DisputeStatus.UNDER_REVIEW;
            case DISPUTE_CLOSED_WON -> status == DisputeStatus.WON;
            case DISPUTE_CLOSED_LOST -> status == DisputeStatus.LOST;
            case WARNING_CLOSED -> status == DisputeStatus.WARNING_CLOSED;
            default -> false;
        };
    }

    private static boolean nonCaptureTerminal(PaymentStatus status) {
        return status == PaymentStatus.FAILED || status == PaymentStatus.EXPIRED
                || status == PaymentStatus.CANCELED;
    }

    private Map<String, Object> common(long orderId, String paymentId, int attempt,
                                       long version, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2); data.put("order_id", Long.toString(orderId));
        data.put("payment_transaction_id", paymentId); data.put("payment_attempt_number", attempt);
        data.put("financial_state_version", version); data.put("occurred_at", now.toString());
        return data;
    }

    private String evidence(String dbStatus, StripeWebhookEvent fact, Instant now) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("db_status", dbStatus); value.put("provider_status", fact.providerStatus());
        value.put("provider_object_id", fact.providerObjectId()); value.put("observed_at", now.toString());
        value.put("provider_fact", fact.sanitizedObjectJson());
        return write(List.of(value));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "PROVIDER_REJECTED"
                : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static final class MutableCounts {
        int scanned; int drift; int corrected; int reviews;
        long captured; long refunded; long disputed; long fees; long payouts;
        ReconciliationPort.Counts snapshot() {
            return new ReconciliationPort.Counts(scanned, drift, corrected, reviews,
                    captured, refunded, disputed, fees, payouts);
        }
    }
}
