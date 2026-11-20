package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.ConcurrentFinancialMutationException;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;

@Component
public final class JdbcFinancialCommitAdapter implements FinancialCommitPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JdbcBalanceLedgerAdapter ledger;

    public JdbcFinancialCommitAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ledger = new JdbcBalanceLedgerAdapter(jdbc, transactions);
    }

    @Override
    public void commit(CommitBatch batch) {
        transactions.executeWithoutResult(ignored -> {
            if (batch.disputeCreate() != null) insertDispute(batch.disputeCreate());
            if (batch.payment() != null) updatePayment(batch.payment());
            if (batch.refund() != null) updateRefund(batch.refund());
            if (batch.dispute() != null) updateDispute(batch.dispute());
            batch.history().forEach(this::insertHistory);
            batch.journals().forEach(this::postJournal);
            batch.outbox().forEach(this::insertOutbox);
            if (batch.reviewCase() != null) insertReviewCase(batch.reviewCase());
            if (batch.inboxCompletion() != null) completeInbox(batch.inboxCompletion());
        });
    }

    private void insertDispute(CommitBatch.DisputeCreate create) {
        jdbc.sql("""
                INSERT INTO payment_disputes
                  (id,payment_transaction_id,order_id,provider_dispute_id,amount_minor,
                   currency,reason_code,status,funds_state,evidence_due_at,opened_at,
                   last_provider_event_created_at,last_provider_event_id)
                VALUES (:id,:payment_id,:order_id,:provider_dispute_id,:amount_minor,
                        :currency,:reason_code,:status,:funds_state,:evidence_due_at,:opened_at,
                        :event_created_at,:event_id)
                """).param("id", create.id()).param("payment_id", create.paymentId())
                .param("order_id", create.orderId()).param("provider_dispute_id", create.providerDisputeId())
                .param("amount_minor", create.amountMinor()).param("currency", create.currency())
                .param("reason_code", create.reasonCode()).param("status", create.status().name())
                .param("funds_state", create.fundsState().name())
                .param("evidence_due_at", timestamp(create.evidenceDueAt()))
                .param("opened_at", timestamp(create.openedAt()))
                .param("event_created_at", timestamp(create.providerEventCreatedAt()))
                .param("event_id", create.providerEventId()).update();
    }

    private void postJournal(CommitBatch.JournalRecord record) {
        if (record.reversalOfJournalId() == null) {
            ledger.post(new com.platform.paymentservice.application.port.out.BalanceLedgerPort.Journal(
                    record.id(), record.paymentId(), record.refundId(), record.disputeId(),
                    record.journalType(), record.providerBalanceTransactionId(), record.sourceType(),
                    record.sourceId(), record.amountMinor(), record.currency(), record.occurredAt(),
                    record.providerObservedAt(), record.linesJson()));
            return;
        }
        jdbc.sql("""
                CALL post_financial_journal(
                  CAST(:id AS varchar),CAST(:payment_id AS varchar),CAST(:refund_id AS varchar),
                  CAST(:dispute_id AS varchar),CAST(:journal_type AS varchar),
                  CAST(:balance_transaction_id AS varchar),CAST(:source_type AS varchar),
                  CAST(:source_id AS varchar),CAST(:reversal_id AS varchar),:amount_minor,
                  CAST(:currency AS char(3)),CAST(:occurred_at AS timestamptz),
                  CAST(:observed_at AS timestamptz),CAST(:lines AS jsonb))
                """).param("id", record.id()).param("payment_id", record.paymentId())
                .param("refund_id", record.refundId()).param("dispute_id", record.disputeId())
                .param("journal_type", record.journalType())
                .param("balance_transaction_id", record.providerBalanceTransactionId())
                .param("source_type", record.sourceType()).param("source_id", record.sourceId())
                .param("reversal_id", record.reversalOfJournalId())
                .param("amount_minor", record.amountMinor()).param("currency", record.currency())
                .param("occurred_at", timestamp(record.occurredAt()))
                .param("observed_at", timestamp(record.providerObservedAt()))
                .param("lines", record.linesJson()).update();
    }

    private void updatePayment(CommitBatch.PaymentChange change) {
        int changed = jdbc.sql("""
                UPDATE payment_transactions
                   SET status = :new_status,
                       financial_state_version = :financial_version,
                       version = version + 1,
                       last_provider_event_created_at = COALESCE(:event_created_at, last_provider_event_created_at),
                       last_provider_event_id = COALESCE(:event_id, last_provider_event_id),
                       provider_transaction_id = COALESCE(:provider_transaction_id, provider_transaction_id),
                       provider_charge_id = COALESCE(:provider_charge_id, provider_charge_id),
                       provider_status_snapshot = COALESCE(:provider_status, provider_status_snapshot),
                       provider_synced_at = COALESCE(:provider_synced_at, provider_synced_at),
                       succeeded_at = COALESCE(:succeeded_at, succeeded_at),
                       terminal_at = COALESCE(:terminal_at, terminal_at),
                       client_secret_ciphertext = CASE WHEN :clear_secret THEN NULL ELSE client_secret_ciphertext END,
                       client_secret_key_id = CASE WHEN :clear_secret THEN NULL ELSE client_secret_key_id END,
                       requires_manual_review = requires_manual_review OR :manual_review,
                       updated_at = now()
                 WHERE id = :id AND version = :expected_version AND status = :old_status
                """)
                .param("new_status", change.newStatus().name())
                .param("financial_version", change.newFinancialStateVersion())
                .param("event_created_at", timestamp(change.providerEventCreatedAt()))
                .param("event_id", change.providerEventId())
                .param("provider_transaction_id", change.providerTransactionId())
                .param("provider_charge_id", change.providerChargeId())
                .param("provider_status", change.providerStatusSnapshot())
                .param("provider_synced_at", timestamp(change.providerSyncedAt()))
                .param("succeeded_at", timestamp(change.succeededAt()))
                .param("terminal_at", timestamp(change.terminalAt()))
                .param("clear_secret", change.clearClientSecret())
                .param("manual_review", change.requiresManualReview())
                .param("id", change.id())
                .param("expected_version", change.expectedVersion())
                .param("old_status", change.oldStatus().name())
                .update();
        requireOne(changed, "payment", change.id());
    }

    private void updateRefund(CommitBatch.RefundChange change) {
        int changed = jdbc.sql("""
                UPDATE refund_transactions
                   SET status = :new_status, version = version + 1,
                       last_provider_event_created_at = COALESCE(:event_created_at, last_provider_event_created_at),
                       last_provider_event_id = COALESCE(:event_id, last_provider_event_id),
                       provider_refund_id = COALESCE(:provider_refund_id, provider_refund_id),
                       provider_failure_reason = COALESCE(:failure_reason, provider_failure_reason),
                       provider_status_snapshot = COALESCE(:provider_status, provider_status_snapshot),
                       provider_synced_at = COALESCE(:provider_synced_at, provider_synced_at),
                       accepted_at = COALESCE(:accepted_at, accepted_at),
                       finalized_at = COALESCE(:finalized_at, finalized_at),
                       reversed_at = COALESCE(:reversed_at, reversed_at), updated_at = now()
                 WHERE id = :id AND version = :expected_version AND status = :old_status
                """)
                .param("new_status", change.newStatus().name())
                .param("event_created_at", timestamp(change.providerEventCreatedAt()))
                .param("event_id", change.providerEventId())
                .param("provider_refund_id", change.providerRefundId())
                .param("failure_reason", change.failureReason())
                .param("provider_status", change.providerStatusSnapshot())
                .param("provider_synced_at", timestamp(change.providerSyncedAt()))
                .param("accepted_at", timestamp(change.acceptedAt()))
                .param("finalized_at", timestamp(change.finalizedAt()))
                .param("reversed_at", timestamp(change.reversedAt()))
                .param("id", change.id()).param("expected_version", change.expectedVersion())
                .param("old_status", change.oldStatus().name()).update();
        requireOne(changed, "refund", change.id());
    }

    private void updateDispute(CommitBatch.DisputeChange change) {
        int changed = jdbc.sql("""
                UPDATE payment_disputes
                   SET status = :new_status, funds_state = :funds_state, version = version + 1,
                       last_provider_event_created_at = :event_created_at,
                       last_provider_event_id = :event_id,
                       evidence_due_at = COALESCE(:evidence_due_at, evidence_due_at),
                       closed_at = COALESCE(:closed_at, closed_at), updated_at = now()
                 WHERE id = :id AND version = :expected_version AND status = :old_status
                """)
                .param("new_status", change.newStatus().name())
                .param("funds_state", change.newFundsState().name())
                .param("event_created_at", timestamp(change.providerEventCreatedAt()))
                .param("event_id", change.providerEventId())
                .param("evidence_due_at", timestamp(change.evidenceDueAt()))
                .param("closed_at", timestamp(change.closedAt()))
                .param("id", change.id()).param("expected_version", change.expectedVersion())
                .param("old_status", change.oldStatus().name()).update();
        requireOne(changed, "dispute", change.id());
    }

    private void insertHistory(CommitBatch.HistoryRecord record) {
        jdbc.sql("""
                INSERT INTO payment_status_history
                  (payment_transaction_id, refund_transaction_id, dispute_transaction_id,
                   transaction_type, transition_id, old_status, new_status,
                   financial_state_version, triggered_by, cause_ref, actor,
                   provider_response, created_at)
                VALUES (:payment_id, :refund_id, :dispute_id, :transaction_type,
                        :transition_id, :old_status, :new_status, :financial_version,
                        :triggered_by, :cause_ref, :actor, CAST(:provider_response AS jsonb),
                        :created_at)
                """)
                .param("payment_id", record.paymentId()).param("refund_id", record.refundId())
                .param("dispute_id", record.disputeId()).param("transaction_type", record.transactionType())
                .param("transition_id", record.transitionId()).param("old_status", record.oldStatus())
                .param("new_status", record.newStatus()).param("financial_version", record.financialStateVersion())
                .param("triggered_by", record.triggeredBy()).param("cause_ref", record.causeRef())
                .param("actor", record.actor()).param("provider_response", record.providerResponseJson())
                .param("created_at", timestamp(record.createdAt())).update();
    }

    private void insertOutbox(CommitBatch.OutboxRecord record) {
        jdbc.sql("""
                INSERT INTO outbox_events
                  (event_id, aggregate_id, event_type, partition_key, headers, payload, created_at)
                VALUES (:event_id, :aggregate_id, :event_type, :partition_key,
                        CAST(:headers AS jsonb), CAST(:payload AS jsonb), :created_at)
                """)
                .param("event_id", record.eventId()).param("aggregate_id", record.aggregateId())
                .param("event_type", record.eventType()).param("partition_key", record.partitionKey())
                .param("headers", record.headersJson()).param("payload", record.payloadJson())
                .param("created_at", timestamp(record.createdAt())).update();
    }

    private void insertReviewCase(CommitBatch.ReviewCaseRecord record) {
        jdbc.sql("""
                INSERT INTO payment_review_cases
                  (id, payment_transaction_id, refund_transaction_id, reason_code,
                   case_status, evidence, opened_by, opened_at)
                VALUES (:id, :payment_id, :refund_id, :reason_code, 'OPEN',
                        CAST(:evidence AS jsonb), :opened_by, :opened_at)
                ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                """)
                .param("id", record.id()).param("payment_id", record.paymentId())
                .param("refund_id", record.refundId()).param("reason_code", record.reasonCode())
                .param("evidence", record.evidenceJson()).param("opened_by", record.openedBy())
                .param("opened_at", timestamp(record.openedAt())).update();
    }

    private void completeInbox(CommitBatch.InboxCompletion completion) {
        int changed = jdbc.sql("""
                UPDATE webhook_logs
                   SET status = :status, processing_error = :error,
                       processed_at = :processed_at, lease_owner = NULL,
                       lease_expires_at = NULL,
                       provider_object_id = COALESCE(:provider_object_id, provider_object_id),
                       payment_transaction_id = COALESCE(:payment_id, payment_transaction_id),
                       refund_transaction_id = COALESCE(:refund_id, refund_transaction_id),
                       dispute_transaction_id = COALESCE(:dispute_id, dispute_transaction_id)
                 WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease_token
                   AND lease_expires_at > :processed_at
                """)
                .param("status", completion.terminalStatus())
                .param("error", completion.processingError())
                .param("processed_at", timestamp(completion.processedAt()))
                .param("provider_object_id", completion.providerObjectId())
                .param("payment_id", completion.paymentId())
                .param("refund_id", completion.refundId())
                .param("dispute_id", completion.disputeId())
                .param("id", completion.inboxId()).param("lease_token", completion.leaseToken())
                .update();
        requireOne(changed, "webhook-inbox", Long.toString(completion.inboxId()));
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireOne(int changed, String type, String id) {
        if (changed != 1) throw new ConcurrentFinancialMutationException(type, id);
    }
}
