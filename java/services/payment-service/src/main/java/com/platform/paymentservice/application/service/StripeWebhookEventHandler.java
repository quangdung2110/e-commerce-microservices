package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.RefundProviderOutcome;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.port.out.StripeWebhookEventParserPort;
import com.platform.paymentservice.application.port.out.WebhookEventHandlerPort;
import com.platform.paymentservice.application.port.out.WebhookPaymentQueryPort;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;
import com.platform.paymentservice.domain.CheckpointDecision;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.PaymentTransition;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.RefundStateMachine;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.RefundTransition;
import com.platform.paymentservice.domain.RefundTrigger;
import com.platform.paymentservice.domain.DisputeStateMachine;
import com.platform.paymentservice.domain.DisputeState;
import com.platform.paymentservice.domain.DisputeStatus;
import com.platform.paymentservice.domain.DisputeFundsState;
import com.platform.paymentservice.domain.DisputeTransition;
import com.platform.paymentservice.domain.DisputeTrigger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StripeWebhookEventHandler implements WebhookEventHandlerPort {
    private static final String PROCESSING = "payment_intent.processing";
    private static final String SUCCEEDED = "payment_intent.succeeded";
    private static final String PAYMENT_FAILED = "payment_intent.payment_failed";
    private static final String CANCELED = "payment_intent.canceled";
    private static final String CHARGE_REFUNDED = "charge.refunded";
    private static final String REFUND_UPDATED = "refund.updated";
    private static final String REFUND_FAILED = "refund.failed";
    private static final String DISPUTE_CREATED = "charge.dispute.created";
    private static final String DISPUTE_UPDATED = "charge.dispute.updated";
    private static final String DISPUTE_CLOSED = "charge.dispute.closed";
    private static final String DISPUTE_WITHDRAWN = "charge.dispute.funds_withdrawn";
    private static final String DISPUTE_REINSTATED = "charge.dispute.funds_reinstated";

    private final StripeWebhookEventParserPort parser;
    private final WebhookPaymentQueryPort payments;
    private final WebhookProcessingPort inbox;
    private final FinancialCommitPort commits;
    private final PaymentStateMachine stateMachine;
    private final IdGeneratorPort ids;
    private final ObjectMapper json;
    private final DeclinePaymentService declines;
    private final ProviderCurrentFactPort currentFacts;
    private final ClockPort clock;
    private final RefundStateMachine refundStateMachine = new RefundStateMachine();
    private final DisputeStateMachine disputeStateMachine = new DisputeStateMachine();

    public StripeWebhookEventHandler(StripeWebhookEventParserPort parser,
                                     WebhookPaymentQueryPort payments,
                                     WebhookProcessingPort inbox,
                                     FinancialCommitPort commits,
                                     PaymentStateMachine stateMachine,
                                     IdGeneratorPort ids,
                                     ObjectMapper json) {
        this(parser, payments, inbox, commits, stateMachine, ids, json, null, null, Instant::now);
    }

    public StripeWebhookEventHandler(StripeWebhookEventParserPort parser,
                                     WebhookPaymentQueryPort payments,
                                     WebhookProcessingPort inbox,
                                     FinancialCommitPort commits,
                                     PaymentStateMachine stateMachine,
                                     IdGeneratorPort ids,
                                     ObjectMapper json,
                                     DeclinePaymentService declines) {
        this(parser, payments, inbox, commits, stateMachine, ids, json, null, declines, Instant::now);
    }

    public StripeWebhookEventHandler(StripeWebhookEventParserPort parser,
                                     WebhookPaymentQueryPort payments,
                                     WebhookProcessingPort inbox,
                                     FinancialCommitPort commits,
                                     PaymentStateMachine stateMachine,
                                     IdGeneratorPort ids,
                                     ObjectMapper json,
                                     ProviderCurrentFactPort currentFacts,
                                     DeclinePaymentService declines, ClockPort clock) {
        this.parser = Objects.requireNonNull(parser);
        this.payments = Objects.requireNonNull(payments);
        this.inbox = Objects.requireNonNull(inbox);
        this.commits = Objects.requireNonNull(commits);
        this.stateMachine = Objects.requireNonNull(stateMachine);
        this.ids = Objects.requireNonNull(ids);
        this.json = Objects.requireNonNull(json);
        this.currentFacts = currentFacts;
        this.declines = declines;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public HandlingResult handle(WebhookProcessingPort.Claimed claim, byte[] exactBody, Instant now)
            throws ProcessingException {
        StripeWebhookEvent event;
        try {
            event = parser.parse(exactBody);
        } catch (StripeWebhookEventParserPort.InvalidStripeEventException invalid) {
            throw new ProcessingException(invalid.getMessage(), false, invalid);
        }
        if (!claim.stripeEventId().equals(event.eventId())
                || !claim.eventType().equals(event.eventType())
                || !claim.eventCreatedAt().equals(event.eventCreatedAt())) {
            throw new ProcessingException("verified inbox metadata does not match exact Stripe body", false);
        }

        if (isRefundEvent(event.eventType())) return refund(claim, event, now);
        if (isDisputeEvent(event.eventType())) return dispute(claim, event, now);

        if (!PROCESSING.equals(event.eventType()) && !SUCCEEDED.equals(event.eventType())
                && !PAYMENT_FAILED.equals(event.eventType()) && !CANCELED.equals(event.eventType())) {
            return inbox.completeIgnored(claim, "UNSUPPORTED_EVENT_TYPE", now,
                    event.providerObjectId(), null, null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }

        Optional<WebhookPaymentQueryPort.PaymentSnapshot> resolved = resolvePayment(event);
        if (resolved.isEmpty()) {
            return inbox.completeIgnored(claim, "PAYMENT_NOT_MAPPED", now,
                    event.providerObjectId(), null, null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        var payment = resolved.get();

        if (event.amountMinor() == null || event.currency() == null
                || event.amountMinor() != payment.amountMinor()
                || !event.currency().equals(payment.currency())) {
            Map<String, Object> mismatch = new LinkedHashMap<>();
            mismatch.put("stripe_event_id", event.eventId());
            mismatch.put("expected_amount_minor", payment.amountMinor());
            mismatch.put("expected_currency", payment.currency());
            mismatch.put("provider_amount_minor", event.amountMinor());
            mismatch.put("provider_currency", event.currency());
            mismatch.put("observed_at", now.toString());
            String evidence = write(List.of(mismatch));
            return inbox.completeWithReview(claim, payment.id(), event.providerObjectId(),
                    "AMOUNT_MISMATCH", evidence, ids.nextReviewCaseId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }

        CheckpointDecision checkpoint = payment.checkpoint() == null
                ? CheckpointDecision.APPLY
                : payment.checkpoint().decide(event.eventCreatedAt(), event.eventId());
        if (checkpoint == CheckpointDecision.STALE || checkpoint == CheckpointDecision.DUPLICATE) {
            return inbox.completeIgnored(claim, checkpoint.name(), now,
                    event.providerObjectId(), payment.id(), null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (checkpoint == CheckpointDecision.TIE_RETRIEVE_REQUIRED) {
            if (currentFacts == null)
                throw new ProcessingException("provider checkpoint tie requires Stripe retrieve", true);
            ProviderCurrentFactPort.LookupResult lookup = currentFacts.retrievePaymentIntent(
                    event, payment.providerTransactionId());
            now = clock.now();
            if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
                throw new ProcessingException(unavailable.errorCode(), true);
            if (lookup instanceof ProviderCurrentFactPort.NoTransition noTransition) {
                return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                        payment.version(), noTransition.reason(), event.providerObjectId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
            if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
                return inbox.completeWithReview(claim, payment.id(), event.providerObjectId(),
                        review.reasonCode(), review.evidenceJson(), ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
            event = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
            if (!matches(event, payment.amountMinor(), payment.currency())) {
                return inbox.completeWithReview(claim, payment.id(), event.providerObjectId(),
                        "AMOUNT_MISMATCH", mismatchEvidence(event, payment.amountMinor(),
                                payment.currency(), now), ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
        }

        if (PROCESSING.equals(event.eventType())) return processing(claim, event, payment, now);
        if (SUCCEEDED.equals(event.eventType())) return succeeded(claim, event, payment, now);
        if (PAYMENT_FAILED.equals(event.eventType())) return paymentFailed(claim, event, payment, now);
        return canceled(claim, event, payment, now);
    }

    private HandlingResult dispute(WebhookProcessingPort.Claimed claim,
                                   StripeWebhookEvent event, Instant now)
            throws ProcessingException {
        Optional<WebhookPaymentQueryPort.DisputeSnapshot> existing =
                event.providerDisputeId() == null ? Optional.empty()
                        : payments.findDisputeByProviderDisputeId(event.providerDisputeId());
        Optional<WebhookPaymentQueryPort.PaymentSnapshot> resolvedPayment = resolvePayment(event);
        if (existing.isEmpty() && resolvedPayment.isEmpty()) {
            return inbox.completeIgnored(claim, "DISPUTE_NOT_MAPPED", now,
                    event.providerObjectId(), null, null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        String paymentId = existing.map(WebhookPaymentQueryPort.DisputeSnapshot::paymentId)
                .orElseGet(() -> resolvedPayment.orElseThrow().id());
        long expectedAmount = existing.map(WebhookPaymentQueryPort.DisputeSnapshot::amountMinor)
                .orElseGet(() -> resolvedPayment.orElseThrow().amountMinor());
        String expectedCurrency = existing.map(WebhookPaymentQueryPort.DisputeSnapshot::currency)
                .orElseGet(() -> resolvedPayment.orElseThrow().currency());
        if (event.amountMinor() == null || event.currency() == null
                || event.amountMinor() != expectedAmount
                || !event.currency().equals(expectedCurrency)) {
            return inbox.completeFinancialObjectWithReview(claim, paymentId, null,
                    existing.map(WebhookPaymentQueryPort.DisputeSnapshot::id).orElse(null),
                    existing.map(WebhookPaymentQueryPort.DisputeSnapshot::version).orElse(null),
                    event.providerObjectId(), "AMOUNT_MISMATCH",
                    mismatchEvidence(event, expectedAmount, expectedCurrency, now),
                    ids.nextReviewCaseId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (existing.isEmpty()) {
            if (!DISPUTE_CREATED.equals(event.eventType())) {
                return inbox.completeIgnored(claim, "DISPUTE_NOT_CREATED", now,
                        event.providerObjectId(), paymentId, null, null)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
            return createDispute(claim, event, resolvedPayment.orElseThrow(), now);
        }
        var dispute = existing.get();
        CheckpointDecision checkpoint = dispute.checkpoint().decide(
                event.eventCreatedAt(), event.eventId());
        if (checkpoint == CheckpointDecision.STALE || checkpoint == CheckpointDecision.DUPLICATE) {
            return inbox.completeIgnored(claim, checkpoint.name(), now, event.providerObjectId(),
                    dispute.paymentId(), null, dispute.id())
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (checkpoint == CheckpointDecision.TIE_RETRIEVE_REQUIRED) {
            if (currentFacts == null)
                throw new ProcessingException("dispute checkpoint tie requires Stripe retrieve", true);
            ProviderCurrentFactPort.LookupResult lookup = currentFacts.retrieveDispute(
                    event, event.providerDisputeId());
            now = clock.now();
            if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
                throw new ProcessingException(unavailable.errorCode(), true);
            if (lookup instanceof ProviderCurrentFactPort.NoTransition noTransition)
                return ignoreAndAdvanceDispute(claim, event, dispute, now, noTransition.reason());
            if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
                return inbox.completeFinancialObjectWithReview(claim, dispute.paymentId(), null,
                        dispute.id(), dispute.version(), event.providerObjectId(),
                        review.reasonCode(), review.evidenceJson(), ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
            event = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
            if (!matches(event, dispute.amountMinor(), dispute.currency())) {
                return inbox.completeFinancialObjectWithReview(claim, dispute.paymentId(), null,
                        dispute.id(), dispute.version(), event.providerObjectId(), "AMOUNT_MISMATCH",
                        mismatchEvidence(event, dispute.amountMinor(), dispute.currency(), now),
                        ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
        }
        if (DISPUTE_CREATED.equals(event.eventType()))
            return ignoreAndAdvanceDispute(claim, event, dispute, now, "ALREADY_CREATED");

        DisputeTrigger trigger = disputeTrigger(event);
        if ((trigger == DisputeTrigger.FUNDS_WITHDRAWN
                && dispute.fundsState() == DisputeFundsState.WITHDRAWN)
                || (trigger == DisputeTrigger.FUNDS_REINSTATED
                && dispute.fundsState() == DisputeFundsState.REINSTATED)
                || (trigger == DisputeTrigger.DISPUTE_UPDATED
                && dispute.status() != DisputeStatus.OPEN)) {
            return ignoreAndAdvanceDispute(claim, event, dispute, now, "STATE_PRECEDENCE");
        }
        final DisputeTransition transition;
        try {
            transition = disputeStateMachine.transition(
                    new DisputeState(dispute.status(), dispute.fundsState()), trigger);
        } catch (RuntimeException invalid) {
            return ignoreAndAdvanceDispute(claim, event, dispute, now, "STATE_PRECEDENCE");
        }
        return commitDispute(claim, event, dispute, transition, now);
    }

    private HandlingResult createDispute(
            WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
            WebhookPaymentQueryPort.PaymentSnapshot payment, Instant now) {
        DisputeTransition transition = disputeStateMachine.open(DisputeTrigger.DISPUTE_CREATED);
        String disputeId = ids.nextDisputeTransactionId();
        long financialVersion = payment.financialStateVersion() + 1;
        var paymentChange = sameStatusFinancialChange(payment, financialVersion, true);
        var create = new CommitBatch.DisputeCreate(disputeId, payment.id(), payment.orderId(),
                event.providerDisputeId(), event.amountMinor(), event.currency(),
                normalizeMachineCode(event.reasonCode()), transition.to().status(),
                transition.to().fundsState(), event.evidenceDueAt(), now,
                event.eventCreatedAt(), event.eventId());
        var history = new CommitBatch.HistoryRecord(payment.id(), null, disputeId,
                "DISPUTE", transition.transitionId(), null, transition.to().status().name(),
                financialVersion, triggeredBy(claim), causeRef(claim, event.eventId()),
                claim.replayActor(), event.sanitizedObjectJson(), now);
        var review = new CommitBatch.ReviewCaseRecord(ids.nextReviewCaseId(), payment.id(), null,
                "DISPUTE_OPENED", disputeReviewEvidence(event, disputeId, refundOverlap(payment), now),
                "WEBHOOK", now);
        var completion = new CommitBatch.InboxCompletion(claim.inboxId(), claim.leaseToken(),
                "PROCESSED", null, now, event.providerObjectId(), payment.id(), null, disputeId);
        commits.commit(new CommitBatch(paymentChange, null, null, List.of(history),
                List.of(disputeOutbox(payment.id(), payment.orderId(), payment.attemptNumber(),
                        disputeId, event.amountMinor(), event.currency(), financialVersion,
                        transition.transitionId(), event.reasonCode(), event.evidenceDueAt(), now)),
                review, completion, create, List.of()));
        return HandlingResult.COMMITTED;
    }

    private HandlingResult commitDispute(
            WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
            WebhookPaymentQueryPort.DisputeSnapshot dispute,
            DisputeTransition transition, Instant now) {
        boolean financial = transition.fact().isPresent();
        long financialVersion = dispute.financialStateVersion() + (financial ? 1 : 0);
        CommitBatch.PaymentChange paymentChange = financial
                ? new CommitBatch.PaymentChange(dispute.paymentId(), dispute.paymentVersion(),
                dispute.paymentStatus(), dispute.paymentStatus(), financialVersion,
                null, null, null, null, null, null, false, false) : null;
        boolean closed = transition.to().status() == DisputeStatus.WON
                || transition.to().status() == DisputeStatus.LOST
                || transition.to().status() == DisputeStatus.WARNING_CLOSED;
        var disputeChange = new CommitBatch.DisputeChange(dispute.id(), dispute.version(),
                dispute.status(), transition.to().status(), transition.to().fundsState(),
                event.eventCreatedAt(), event.eventId(), event.evidenceDueAt(), closed ? now : null);
        var history = new CommitBatch.HistoryRecord(dispute.paymentId(), null, dispute.id(),
                "DISPUTE", transition.transitionId(), dispute.status().name(),
                transition.to().status().name(), financialVersion, triggeredBy(claim),
                causeRef(claim, event.eventId()), claim.replayActor(),
                event.sanitizedObjectJson(), now);
        List<CommitBatch.OutboxRecord> outbox = transition.fact().isEmpty() ? List.of()
                : List.of(disputeOutbox(dispute.paymentId(), dispute.orderId(), dispute.attemptNumber(),
                dispute.id(), dispute.amountMinor(), dispute.currency(), financialVersion,
                transition.transitionId(), dispute.reasonCode(),
                event.evidenceDueAt() == null ? dispute.evidenceDueAt() : event.evidenceDueAt(), now));
        List<CommitBatch.JournalRecord> journals = switch (transition.transitionId()) {
            case "D3" -> List.of(disputeJournal(event, dispute, now, false));
            case "D4" -> List.of(disputeJournal(event, dispute, now, true));
            default -> List.of();
        };
        var completion = new CommitBatch.InboxCompletion(claim.inboxId(), claim.leaseToken(),
                "PROCESSED", null, now, event.providerObjectId(), dispute.paymentId(), null, dispute.id());
        commits.commit(new CommitBatch(paymentChange, null, disputeChange, List.of(history),
                outbox, null, completion, null, journals));
        return HandlingResult.COMMITTED;
    }

    private HandlingResult ignoreAndAdvanceDispute(
            WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
            WebhookPaymentQueryPort.DisputeSnapshot dispute, Instant now, String reason) {
        return inbox.completeIgnoredAndAdvanceDisputeCheckpoint(claim, dispute.id(),
                dispute.version(), dispute.paymentId(), reason, event.providerObjectId(), now)
                ? HandlingResult.COMMITTED : HandlingResult.FENCED;
    }

    private HandlingResult refund(WebhookProcessingPort.Claimed claim,
                                  StripeWebhookEvent event, Instant now)
            throws ProcessingException {
        Optional<WebhookPaymentQueryPort.RefundSnapshot> resolved = resolveRefund(event);
        if (resolved.isEmpty()) {
            return inbox.completeIgnored(claim, "REFUND_NOT_MAPPED", now,
                    event.providerObjectId(), null, null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        var refund = resolved.get();
        if (event.amountMinor() == null || event.currency() == null
                || event.amountMinor() != refund.amountMinor()
                || !event.currency().equals(refund.currency())) {
            return inbox.completeFinancialObjectWithReview(claim, refund.paymentId(), refund.id(),
                    null, refund.version(), event.providerObjectId(), "AMOUNT_MISMATCH",
                    mismatchEvidence(event, refund.amountMinor(), refund.currency(), now),
                    ids.nextReviewCaseId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        CheckpointDecision checkpoint = refund.checkpoint() == null
                ? CheckpointDecision.APPLY
                : refund.checkpoint().decide(event.eventCreatedAt(), event.eventId());
        if (checkpoint == CheckpointDecision.STALE || checkpoint == CheckpointDecision.DUPLICATE) {
            return inbox.completeIgnored(claim, checkpoint.name(), now, event.providerObjectId(),
                    refund.paymentId(), refund.id(), null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        // ADR-022: charge.refunded carries the charge, not the refund, so it is only a hint that
        // something changed; the Refund object decides. It is retrieved like a checkpoint tie.
        boolean settleFromRefundObject = CHARGE_REFUNDED.equals(event.eventType())
                && refund.status() == RefundStatus.PROCESSING
                && refund.paymentStatus() == PaymentStatus.REFUNDING;
        if (checkpoint == CheckpointDecision.TIE_RETRIEVE_REQUIRED || settleFromRefundObject) {
            if (currentFacts == null)
                throw new ProcessingException("refund settlement requires Stripe retrieve", true);
            ProviderCurrentFactPort.LookupResult lookup = currentFacts.retrieveRefund(
                    event, refund.providerRefundId());
            now = clock.now();
            if (lookup instanceof ProviderCurrentFactPort.TemporarilyUnavailable unavailable)
                throw new ProcessingException(unavailable.errorCode(), true);
            if (lookup instanceof ProviderCurrentFactPort.NoTransition noTransition)
                return ignoreAndAdvanceRefund(claim, event, refund, now, noTransition.reason());
            if (lookup instanceof ProviderCurrentFactPort.ReviewRequired review) {
                return inbox.completeFinancialObjectWithReview(claim, refund.paymentId(), refund.id(),
                        null, refund.version(), event.providerObjectId(), review.reasonCode(),
                        review.evidenceJson(), ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
            event = ((ProviderCurrentFactPort.CurrentFact) lookup).event();
            if (!matches(event, refund.amountMinor(), refund.currency())) {
                return inbox.completeFinancialObjectWithReview(claim, refund.paymentId(), refund.id(),
                        null, refund.version(), event.providerObjectId(), "AMOUNT_MISMATCH",
                        mismatchEvidence(event, refund.amountMinor(), refund.currency(), now),
                        ids.nextReviewCaseId(), now)
                        ? HandlingResult.COMMITTED : HandlingResult.FENCED;
            }
        }

        if (CHARGE_REFUNDED.equals(event.eventType())) {
            // Refund already settled (or not awaiting settlement): nothing to learn from the charge.
            return ignoreAndAdvanceRefund(claim, event, refund, now, "STATE_PRECEDENCE");
        }
        boolean awaitingSettlement = refund.status() == RefundStatus.PROCESSING
                && refund.paymentStatus() == PaymentStatus.REFUNDING;
        boolean settledSuccess = refund.status() == RefundStatus.SUCCESS
                && refund.paymentStatus() == PaymentStatus.REFUNDED;
        return switch (RefundProviderOutcome.of(event.providerStatus())) {
            case SUCCEEDED -> awaitingSettlement
                    ? commitRefund(claim, event, refund, RefundTrigger.REFUND_SUCCESS_CONFIRMED,
                    PaymentTrigger.REFUND_SUCCESS_CONFIRMED, now, null)
                    : ignoreAndAdvanceRefund(claim, event, refund, now, "STATE_PRECEDENCE");
            case FAILED -> {
                String reason = event.failureReason() == null
                        ? "PROVIDER_REJECTED" : event.failureReason();
                if (awaitingSettlement)
                    yield commitRefund(claim, event, refund, RefundTrigger.REFUND_FAILED_CONFIRMED,
                            PaymentTrigger.REFUND_FAILED_CONFIRMED, now, reason);
                if (settledSuccess)
                    yield commitRefund(claim, event, refund, RefundTrigger.REFUND_REVERSED_CONFIRMED,
                            PaymentTrigger.REFUND_REVERSED_CONFIRMED, now, reason);
                yield ignoreAndAdvanceRefund(claim, event, refund, now, "STATE_PRECEDENCE");
            }
            // Stripe needs bank details, or the bank returned the funds: after success that is a
            // reversal; before success it is not final, so a human must look.
            case REQUIRES_ACTION -> {
                if (settledSuccess)
                    yield commitRefund(claim, event, refund, RefundTrigger.REFUND_REVERSED_CONFIRMED,
                            PaymentTrigger.REFUND_REVERSED_CONFIRMED, now, "requires_action");
                if (awaitingSettlement)
                    yield inbox.completeFinancialObjectWithReview(claim, refund.paymentId(),
                            refund.id(), null, refund.version(), event.providerObjectId(),
                            "RECONCILIATION_DRIFT", refundReviewEvidence(event, refund, now),
                            ids.nextReviewCaseId(), now)
                            ? HandlingResult.COMMITTED : HandlingResult.FENCED;
                yield ignoreAndAdvanceRefund(claim, event, refund, now, "STATE_PRECEDENCE");
            }
            case NON_TERMINAL -> ignoreAndAdvanceRefund(claim, event, refund, now,
                    "NON_TERMINAL_REFUND_UPDATE");
        };
    }

    private HandlingResult commitRefund(
            WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
            WebhookPaymentQueryPort.RefundSnapshot refund, RefundTrigger refundTrigger,
            PaymentTrigger paymentTrigger, Instant now, String failureReason) {
        RefundTransition refundTransition = refundStateMachine.transition(refund.status(), refundTrigger);
        PaymentTransition paymentTransition = stateMachine.transition(refund.paymentStatus(), paymentTrigger);
        long financialVersion = refund.financialStateVersion() + 1;
        boolean success = refundTransition.to() == RefundStatus.SUCCESS;
        boolean reversed = refundTransition.to() == RefundStatus.REVERSED;
        boolean review = refundTransition.to() == RefundStatus.FAILED || reversed;
        var paymentChange = new CommitBatch.PaymentChange(refund.paymentId(), refund.paymentVersion(),
                refund.paymentStatus(), paymentTransition.to(), financialVersion,
                null, null, null, null, null, null, false, review);
        var refundChange = new CommitBatch.RefundChange(refund.id(), refund.version(), refund.status(),
                refundTransition.to(), event.eventCreatedAt(), event.eventId(),
                event.providerRefundId(), failureReason, null,
                success || refundTransition.to() == RefundStatus.FAILED ? now : null,
                reversed ? now : null, event.providerStatus(), now);
        String source = triggeredBy(claim);
        String cause = causeRef(claim, event.eventId());
        var refundHistory = new CommitBatch.HistoryRecord(refund.paymentId(), refund.id(), null,
                "REFUND", refundTransition.transitionId(), refund.status().name(),
                refundTransition.to().name(), financialVersion, source, cause, claim.replayActor(),
                event.sanitizedObjectJson(), now);
        var paymentHistory = new CommitBatch.HistoryRecord(refund.paymentId(), null, null,
                "PAYMENT", paymentTransition.transitionId(), refund.paymentStatus().name(),
                paymentTransition.to().name(), financialVersion, source, cause, claim.replayActor(),
                event.sanitizedObjectJson(), now);
        CommitBatch.ReviewCaseRecord reviewCase = review
                ? new CommitBatch.ReviewCaseRecord(ids.nextReviewCaseId(), refund.paymentId(),
                refund.id(), reversed ? "REFUND_REVERSED" : "REFUND_FAILED_AFTER_ACCEPT",
                refundReviewEvidence(event, refund, now), "WEBHOOK", now) : null;
        var completion = new CommitBatch.InboxCompletion(claim.inboxId(), claim.leaseToken(),
                "PROCESSED", null, now, event.providerObjectId(), refund.paymentId(), refund.id(), null);
        List<CommitBatch.JournalRecord> journals = success || reversed
                ? List.of(refundJournal(event, refund, now, reversed)) : List.of();
        commits.commit(new CommitBatch(paymentChange, refundChange, null,
                List.of(refundHistory, paymentHistory),
                List.of(refundOutbox(refund, refundTransition.to(), failureReason, now)),
                reviewCase, completion, null, journals));
        return HandlingResult.COMMITTED;
    }

    private HandlingResult ignoreAndAdvanceRefund(
            WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
            WebhookPaymentQueryPort.RefundSnapshot refund, Instant now, String reason) {
        return inbox.completeIgnoredAndAdvanceRefundCheckpoint(claim, refund.id(), refund.version(),
                refund.paymentId(), reason, event.providerObjectId(), now)
                ? HandlingResult.COMMITTED : HandlingResult.FENCED;
    }

    private HandlingResult canceled(WebhookProcessingPort.Claimed claim,
                                    StripeWebhookEvent event,
                                    WebhookPaymentQueryPort.PaymentSnapshot payment,
                                    Instant now) throws ProcessingException {
        if (payment.status() == PaymentStatus.SUCCESS
                || payment.status() == PaymentStatus.REFUNDING
                || payment.status() == PaymentStatus.REFUNDED
                || payment.status() == PaymentStatus.FAILED
                || payment.status() == PaymentStatus.EXPIRED
                || payment.status() == PaymentStatus.CANCELED) {
            return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                    payment.version(), "STATE_PRECEDENCE", event.providerObjectId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (payment.status() != PaymentStatus.PENDING && payment.status() != PaymentStatus.PROCESSING) {
            throw new ProcessingException("payment_intent.canceled cannot apply from "
                    + payment.status(), false);
        }
        String reason = payments.findCancelOperationReason(payment.id()).orElse("PROVIDER_CANCELED");
        PaymentTrigger trigger;
        String eventType;
        String schema;
        Map<String, Object> extra = new LinkedHashMap<>();
        if ("SESSION_EXPIRED".equals(reason)) {
            trigger = PaymentTrigger.PAYMENT_SESSION_EXPIRED;
            eventType = "com.ecommerce.payment.transaction.expired.v2";
            schema = "payment-expired.schema.json";
        } else if (reason.startsWith("DECLINED_") || "DECLINE_LIMIT".equals(reason)) {
            trigger = PaymentTrigger.PAYMENT_FAILED_CONFIRMED;
            eventType = "com.ecommerce.payment.transaction.failed.v2";
            schema = "payment-failed.schema.json";
            extra.put("error_code", "DECLINE_LIMIT".equals(reason)
                    ? "DECLINE_LIMIT_EXCEEDED"
                    : normalizeMachineCode(payment.lastErrorCode()));
        } else {
            trigger = PaymentTrigger.PAYMENT_CANCELED;
            eventType = "com.ecommerce.payment.transaction.canceled.v2";
            schema = "payment-canceled.schema.json";
            extra.put("reason_code", reason.startsWith("ORDER_COMMAND:")
                    ? reason.substring("ORDER_COMMAND:".length()) : "PROVIDER_CANCELED");
        }
        PaymentTransition transition;
        try {
            transition = stateMachine.transition(payment.status(), trigger);
        } catch (RuntimeException invalid) {
            throw new ProcessingException("cancel operation reason conflicts with payment state",
                    true, invalid);
        }
        long financialVersion = payment.financialStateVersion() + 1;
        var change = new CommitBatch.PaymentChange(payment.id(), payment.version(), payment.status(),
                transition.to(), financialVersion, event.eventCreatedAt(), event.eventId(), null,
                event.providerChargeId(), null, now, true, false);
        var history = new CommitBatch.HistoryRecord(payment.id(), null, null, "PAYMENT",
                transition.transitionId(), payment.status().name(), transition.to().name(),
                financialVersion, triggeredBy(claim), causeRef(claim, event.eventId()),
                claim.replayActor(),
                event.sanitizedObjectJson(), now);
        var completion = new CommitBatch.InboxCompletion(claim.inboxId(), claim.leaseToken(),
                "PROCESSED", null, now, event.providerObjectId(), payment.id(), null, null);
        commits.commit(new CommitBatch(change, null, null, List.of(history),
                List.of(paymentOutbox(payment, now, eventType, schema, extra)), null, completion));
        return HandlingResult.COMMITTED;
    }

    private HandlingResult paymentFailed(WebhookProcessingPort.Claimed claim,
                                         StripeWebhookEvent event,
                                         WebhookPaymentQueryPort.PaymentSnapshot payment,
                                         Instant now) throws ProcessingException {
        if (payment.status() == PaymentStatus.SUCCESS
                || payment.status() == PaymentStatus.REFUNDING
                || payment.status() == PaymentStatus.REFUNDED) {
            return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                    payment.version(), "SUCCESS_PRECEDENCE", event.providerObjectId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (payment.status() != PaymentStatus.PENDING && payment.status() != PaymentStatus.PROCESSING) {
            return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                    payment.version(), "STATE_PRECEDENCE", event.providerObjectId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (declines == null) {
            throw new ProcessingException("decline workflow is unavailable", true);
        }
        String declineCode = event.declineCode() == null ? "generic_decline" : event.declineCode();
        DeclinePaymentResult result = declines.record(new DeclinePaymentCommand(
                payment.id(), declineCode, event.errorMessage(), event.outcomeType(),
                event.eventId(), event.sanitizedObjectJson(), claim.inboxId(),
                claim.leaseToken(), event.eventCreatedAt(), claim.replayActor(),
                claim.replayCauseRef()), claim.owner());
        if (result instanceof DeclinePaymentResult.Ignored) {
            return inbox.completeIgnored(claim, "STATE_CHANGED", now,
                    event.providerObjectId(), payment.id(), null, null)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        return HandlingResult.COMMITTED;
    }

    private HandlingResult processing(WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
                                      WebhookPaymentQueryPort.PaymentSnapshot payment, Instant now)
            throws ProcessingException {
        if (payment.status() != PaymentStatus.PENDING) {
            return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                    payment.version(), "STATE_PRECEDENCE", event.providerObjectId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        PaymentTransition transition = stateMachine.transition(
                payment.status(), PaymentTrigger.PROVIDER_PROCESSING);
        commits.commit(batch(claim, event, payment, transition, now, null));
        return HandlingResult.COMMITTED;
    }

    private HandlingResult succeeded(WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
                                     WebhookPaymentQueryPort.PaymentSnapshot payment, Instant now)
            throws ProcessingException {
        if (payment.status() == PaymentStatus.FAILED
                || payment.status() == PaymentStatus.EXPIRED
                || payment.status() == PaymentStatus.CANCELED) {
            String evidence = write(List.of(Map.of(
                    "stripe_event_id", event.eventId(),
                    "db_status", payment.status().name(),
                    "provider_status", "succeeded",
                    "observed_at", now.toString())));
            return inbox.completeWithReview(claim, payment.id(), event.providerObjectId(),
                    "CAPTURE_AFTER_CANCEL", evidence, ids.nextReviewCaseId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (payment.status() == PaymentStatus.SUCCESS
                || payment.status() == PaymentStatus.REFUNDING
                || payment.status() == PaymentStatus.REFUNDED) {
            return inbox.completeIgnoredAndAdvancePaymentCheckpoint(claim, payment.id(),
                    payment.version(), "STATE_PRECEDENCE", event.providerObjectId(), now)
                    ? HandlingResult.COMMITTED : HandlingResult.FENCED;
        }
        if (payment.status() != PaymentStatus.PENDING && payment.status() != PaymentStatus.PROCESSING) {
            throw new ProcessingException("payment_intent.succeeded cannot apply from "
                    + payment.status(), false);
        }
        PaymentTransition transition = stateMachine.transition(
                payment.status(), PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED);
        commits.commit(batch(claim, event, payment, transition, now, successOutbox(event, payment, now)));
        return HandlingResult.COMMITTED;
    }

    private CommitBatch batch(WebhookProcessingPort.Claimed claim, StripeWebhookEvent event,
                              WebhookPaymentQueryPort.PaymentSnapshot payment,
                              PaymentTransition transition, Instant now,
                              CommitBatch.OutboxRecord outbox) {
        long financialVersion = payment.financialStateVersion()
                + (transition.financial() ? 1 : 0);
        boolean succeeded = transition.to() == PaymentStatus.SUCCESS;
        var change = new CommitBatch.PaymentChange(payment.id(), payment.version(),
                payment.status(), transition.to(), financialVersion,
                event.eventCreatedAt(), event.eventId(), null,
                event.providerChargeId(), succeeded ? now : null, null,
                succeeded, false);
        var history = new CommitBatch.HistoryRecord(payment.id(), null, null,
                "PAYMENT", transition.transitionId(), payment.status().name(), transition.to().name(),
                financialVersion, triggeredBy(claim), causeRef(claim, event.eventId()),
                claim.replayActor(),
                event.sanitizedObjectJson(), now);
        var completion = new CommitBatch.InboxCompletion(claim.inboxId(), claim.leaseToken(),
                "PROCESSED", null, now, event.providerObjectId(), payment.id(), null, null);
        List<CommitBatch.JournalRecord> journals = succeeded
                ? List.of(captureJournal(event, payment, now)) : List.of();
        return new CommitBatch(change, null, null, List.of(history),
                outbox == null ? List.of() : List.of(outbox), null, completion,
                null, journals);
    }

    private CommitBatch.JournalRecord captureJournal(
            StripeWebhookEvent event, WebhookPaymentQueryPort.PaymentSnapshot payment, Instant now) {
        if (event.providerChargeId() == null)
            throw new IllegalStateException("successful PaymentIntent is missing latest charge");
        return journal("CAPTURE", payment.id(), null, null,
                event.providerBalanceTransactionId(), "CHARGE_CAPTURE", event.providerChargeId(),
                payment.amountMinor(), payment.currency(), now, event.eventCreatedAt(),
                "PROVIDER_RECEIVABLE", "CUSTOMER_PAYMENT_CLEARING");
    }

    private CommitBatch.JournalRecord refundJournal(
            StripeWebhookEvent event, WebhookPaymentQueryPort.RefundSnapshot refund,
            Instant now, boolean reversal) {
        String sourceId = event.providerRefundId() == null
                ? refund.providerRefundId() : event.providerRefundId();
        if (sourceId == null) sourceId = event.eventId();
        return journal(reversal ? "REFUND_REVERSAL" : "REFUND",
                refund.paymentId(), refund.id(), null, event.providerBalanceTransactionId(),
                reversal ? "REFUND_REVERSAL" : "REFUND", sourceId,
                refund.amountMinor(), refund.currency(), now, event.eventCreatedAt(),
                reversal ? "PROVIDER_RECEIVABLE" : "CUSTOMER_REFUND_CLEARING",
                reversal ? "CUSTOMER_REFUND_CLEARING" : "PROVIDER_RECEIVABLE");
    }

    private CommitBatch.JournalRecord disputeJournal(
            StripeWebhookEvent event, WebhookPaymentQueryPort.DisputeSnapshot dispute,
            Instant now, boolean reinstatement) {
        return journal(reinstatement ? "DISPUTE_REINSTATEMENT" : "DISPUTE_WITHDRAWAL",
                dispute.paymentId(), null, dispute.id(), event.providerBalanceTransactionId(),
                reinstatement ? "DISPUTE_REINSTATEMENT" : "DISPUTE_WITHDRAWAL",
                dispute.id(), dispute.amountMinor(), dispute.currency(), now,
                event.eventCreatedAt(),
                reinstatement ? "PROVIDER_RECEIVABLE" : "DISPUTE_HOLD_OR_LOSS",
                reinstatement ? "DISPUTE_HOLD_OR_LOSS" : "PROVIDER_RECEIVABLE");
    }

    private CommitBatch.JournalRecord journal(
            String type, String paymentId, String refundId, String disputeId,
            String balanceTransactionId, String sourceType, String sourceId,
            long amountMinor, String currency, Instant occurredAt, Instant observedAt,
            String debitAccount, String creditAccount) {
        List<Map<String, Object>> lines = List.of(
                Map.of("account_code", debitAccount, "direction", "DEBIT",
                        "amount_minor", amountMinor, "currency", currency),
                Map.of("account_code", creditAccount, "direction", "CREDIT",
                        "amount_minor", amountMinor, "currency", currency));
        return new CommitBatch.JournalRecord(ids.nextFinancialJournalId(), paymentId,
                refundId, disputeId, type, balanceTransactionId, sourceType, sourceId,
                null, amountMinor, currency, occurredAt, observedAt, write(lines));
    }

    private CommitBatch.OutboxRecord refundOutbox(
            WebhookPaymentQueryPort.RefundSnapshot refund, RefundStatus target,
            String failureReason, Instant now) {
        String suffix;
        String schema;
        if (target == RefundStatus.SUCCESS) {
            suffix = "succeeded";
            schema = "refund-succeeded.schema.json";
        } else if (target == RefundStatus.REVERSED) {
            suffix = "reversed";
            schema = "refund-reversed.schema.json";
        } else {
            suffix = "failed";
            schema = "refund-failed.schema.json";
        }
        String eventType = "com.ecommerce.payment.refund." + suffix + ".v2";
        var eventId = ids.nextEventId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(refund.orderId()));
        data.put("payment_transaction_id", refund.paymentId());
        data.put("payment_attempt_number", refund.attemptNumber());
        data.put("financial_state_version", refund.financialStateVersion() + 1);
        data.put("occurred_at", now.toString());
        data.put("refund_transaction_id", refund.id());
        data.put("refund_amount", Map.of("amount_minor", refund.amountMinor(),
                "currency", refund.currency()));
        if (target == RefundStatus.FAILED)
            data.put("error_code", normalizeMachineCode(failureReason));
        if (target == RefundStatus.REVERSED)
            data.put("reason_code", "REFUND_REVERSED_BY_PROVIDER");
        Map<String, Object> envelope = cloudEvent(eventId, refund.paymentId(), eventType,
                schema, now, data);
        return new CommitBatch.OutboxRecord(eventId, refund.paymentId(), eventType,
                Long.toString(refund.orderId()),
                write(Map.of("event_id", eventId.toString(), "event_type", eventType)),
                write(envelope), now);
    }

    private CommitBatch.OutboxRecord disputeOutbox(
            String paymentId, long orderId, int attemptNumber, String disputeId,
            long amountMinor, String currency, long financialVersion,
            String transitionId, String reasonCode, Instant evidenceDueAt, Instant now) {
        String suffix = switch (transitionId) {
            case "D1" -> "opened";
            case "D3" -> "funds-withdrawn";
            case "D4" -> "funds-reinstated";
            case "D5a" -> "won";
            case "D5b" -> "lost";
            default -> throw new IllegalArgumentException("dispute transition has no fact: " + transitionId);
        };
        String eventType = "com.ecommerce.payment.dispute." + suffix + ".v2";
        var eventId = ids.nextEventId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(orderId));
        data.put("payment_transaction_id", paymentId);
        data.put("payment_attempt_number", attemptNumber);
        data.put("financial_state_version", financialVersion);
        data.put("occurred_at", now.toString());
        data.put("dispute_transaction_id", disputeId);
        data.put("dispute_amount", Map.of("amount_minor", amountMinor, "currency", currency));
        if ("D1".equals(transitionId)) {
            data.put("reason_code", normalizeReason(reasonCode));
            if (evidenceDueAt != null) data.put("evidence_due_at", evidenceDueAt.toString());
        }
        Map<String, Object> envelope = cloudEvent(eventId, paymentId, eventType,
                "dispute-" + suffix + ".schema.json", now, data);
        return new CommitBatch.OutboxRecord(eventId, paymentId, eventType,
                Long.toString(orderId),
                write(Map.of("event_id", eventId.toString(), "event_type", eventType)),
                write(envelope), now);
    }

    private String refundReviewEvidence(StripeWebhookEvent event,
                                        WebhookPaymentQueryPort.RefundSnapshot refund,
                                        Instant now) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("stripe_event_id", event.eventId());
        evidence.put("refund_transaction_id", refund.id());
        evidence.put("db_status", refund.status().name());
        evidence.put("provider_status", event.providerStatus());
        evidence.put("provider_failure_reason", event.failureReason());
        evidence.put("observed_at", now.toString());
        return write(List.of(evidence));
    }

    /** RULE-PAY-023: a dispute on a payment that already has a live refund risks paying the customer twice. */
    private boolean refundOverlap(WebhookPaymentQueryPort.PaymentSnapshot payment) {
        return payments.findRefundByPaymentId(payment.id())
                .map(refund -> refund.status() == RefundStatus.PROCESSING
                        || refund.status() == RefundStatus.SUCCESS)
                .orElse(false);
    }

    private String disputeReviewEvidence(StripeWebhookEvent event, String disputeId,
                                        boolean refundOverlap, Instant now) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (refundOverlap) evidence.put("refund_overlap", true);
        evidence.put("stripe_event_id", event.eventId());
        evidence.put("dispute_transaction_id", disputeId);
        evidence.put("provider_dispute_id", event.providerDisputeId());
        evidence.put("provider_status", event.providerStatus());
        evidence.put("reason_code", normalizeReason(event.reasonCode()));
        evidence.put("evidence_due_at", event.evidenceDueAt() == null
                ? null : event.evidenceDueAt().toString());
        evidence.put("observed_at", now.toString());
        return write(List.of(evidence));
    }

    private String mismatchEvidence(StripeWebhookEvent event, long expectedAmount,
                                    String expectedCurrency, Instant now) {
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("stripe_event_id", event.eventId());
        mismatch.put("expected_amount_minor", expectedAmount);
        mismatch.put("expected_currency", expectedCurrency);
        mismatch.put("provider_amount_minor", event.amountMinor());
        mismatch.put("provider_currency", event.currency());
        mismatch.put("observed_at", now.toString());
        return write(List.of(mismatch));
    }

    private static boolean matches(StripeWebhookEvent event, long amountMinor, String currency) {
        return event.amountMinor() != null && event.currency() != null
                && event.amountMinor() == amountMinor && event.currency().equals(currency);
    }

    private Map<String, Object> cloudEvent(UUID eventId, String paymentId, String eventType,
                                           String schema, Instant now, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "/services/payment");
        envelope.put("subject", "payments/" + paymentId);
        envelope.put("type", eventType);
        envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/" + schema);
        envelope.put("data", data);
        return envelope;
    }

    private CommitBatch.OutboxRecord successOutbox(StripeWebhookEvent event,
                                                    WebhookPaymentQueryPort.PaymentSnapshot payment,
                                                    Instant now) {
        var eventId = ids.nextEventId();
        String eventType = "com.ecommerce.payment.transaction.succeeded.v2";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(payment.orderId()));
        data.put("payment_transaction_id", payment.id());
        data.put("payment_attempt_number", payment.attemptNumber());
        data.put("financial_state_version", payment.financialStateVersion() + 1);
        data.put("occurred_at", now.toString());
        data.put("amount", Map.of("amount_minor", payment.amountMinor(),
                "currency", payment.currency()));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "/services/payment");
        envelope.put("subject", "payments/" + payment.id());
        envelope.put("type", eventType);
        envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/payment-succeeded.schema.json");
        envelope.put("data", data);
        return new CommitBatch.OutboxRecord(eventId, payment.id(), eventType,
                Long.toString(payment.orderId()),
                write(Map.of("event_id", eventId.toString(), "event_type", eventType)),
                write(envelope), now);
    }

    private CommitBatch.OutboxRecord paymentOutbox(
            WebhookPaymentQueryPort.PaymentSnapshot payment, Instant now,
            String eventType, String schemaFile, Map<String, Object> extra) {
        var eventId = ids.nextEventId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(payment.orderId()));
        data.put("payment_transaction_id", payment.id());
        data.put("payment_attempt_number", payment.attemptNumber());
        data.put("financial_state_version", payment.financialStateVersion() + 1);
        data.put("occurred_at", now.toString());
        data.put("amount", Map.of("amount_minor", payment.amountMinor(), "currency", payment.currency()));
        data.putAll(extra);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "/services/payment");
        envelope.put("subject", "payments/" + payment.id());
        envelope.put("type", eventType);
        envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/" + schemaFile);
        envelope.put("data", data);
        return new CommitBatch.OutboxRecord(eventId, payment.id(), eventType,
                Long.toString(payment.orderId()),
                write(Map.of("event_id", eventId.toString(), "event_type", eventType)),
                write(envelope), now);
    }

    private Optional<WebhookPaymentQueryPort.PaymentSnapshot> resolvePayment(StripeWebhookEvent event) {
        if (event.paymentIntentId() != null) {
            var byIntent = payments.findByProviderTransactionId(event.paymentIntentId());
            if (byIntent.isPresent()) return byIntent;
        }
        if (event.providerChargeId() != null) {
            var byCharge = payments.findByProviderChargeId(event.providerChargeId());
            if (byCharge.isPresent()) return byCharge;
        }
        if (event.metadataPaymentTransactionId() != null) {
            return payments.findById(event.metadataPaymentTransactionId());
        }
        return Optional.empty();
    }

    private Optional<WebhookPaymentQueryPort.RefundSnapshot> resolveRefund(StripeWebhookEvent event) {
        if (event.providerRefundId() != null) {
            var byRefund = payments.findRefundByProviderRefundId(event.providerRefundId());
            if (byRefund.isPresent()) return byRefund;
        }
        Optional<WebhookPaymentQueryPort.PaymentSnapshot> payment = resolvePayment(event);
        return payment.flatMap(value -> payments.findRefundByPaymentId(value.id()));
    }

    private CommitBatch.PaymentChange sameStatusFinancialChange(
            WebhookPaymentQueryPort.PaymentSnapshot payment,
            long financialVersion, boolean manualReview) {
        return new CommitBatch.PaymentChange(payment.id(), payment.version(), payment.status(),
                payment.status(), financialVersion, null, null, null, null,
                null, null, false, manualReview);
    }

    private static DisputeTrigger disputeTrigger(StripeWebhookEvent event)
            throws ProcessingException {
        return switch (event.eventType()) {
            case DISPUTE_UPDATED -> DisputeTrigger.DISPUTE_UPDATED;
            case DISPUTE_WITHDRAWN -> DisputeTrigger.FUNDS_WITHDRAWN;
            case DISPUTE_REINSTATED -> DisputeTrigger.FUNDS_REINSTATED;
            case DISPUTE_CLOSED -> switch (event.providerStatus() == null
                    ? "" : event.providerStatus().toLowerCase(java.util.Locale.ROOT)) {
                case "won" -> DisputeTrigger.DISPUTE_CLOSED_WON;
                case "lost" -> DisputeTrigger.DISPUTE_CLOSED_LOST;
                case "warning_closed" -> DisputeTrigger.WARNING_CLOSED;
                default -> throw new ProcessingException(
                        "closed dispute has unsupported provider status", false);
            };
            default -> throw new ProcessingException("unsupported dispute event", false);
        };
    }

    private static boolean isRefundEvent(String eventType) {
        return CHARGE_REFUNDED.equals(eventType) || REFUND_UPDATED.equals(eventType)
                || REFUND_FAILED.equals(eventType);
    }

    private static boolean isDisputeEvent(String eventType) {
        return DISPUTE_CREATED.equals(eventType) || DISPUTE_UPDATED.equals(eventType)
                || DISPUTE_CLOSED.equals(eventType) || DISPUTE_WITHDRAWN.equals(eventType)
                || DISPUTE_REINSTATED.equals(eventType);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("webhook data serialization failed", failure);
        }
    }

    private static String normalizeMachineCode(String value) {
        if (value == null || value.isBlank()) return "PAYMENT_DECLINED";
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String triggeredBy(WebhookProcessingPort.Claimed claim) {
        return claim.replayActor() == null ? "WEBHOOK" : "MANUAL";
    }

    private static String causeRef(WebhookProcessingPort.Claimed claim, String eventId) {
        return claim.replayCauseRef() == null ? eventId : claim.replayCauseRef();
    }
}
