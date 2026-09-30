package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.BalanceLedgerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class JdbcBalanceLedgerAdapter implements BalanceLedgerPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcBalanceLedgerAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<Owner> resolve(String sourceId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id AS payment_id,NULL::varchar AS refund_id,NULL::varchar AS dispute_id
                  FROM payment_transactions WHERE provider_charge_id=:source
                UNION ALL
                SELECT payment_transaction_id,id,NULL::varchar
                  FROM refund_transactions WHERE provider_refund_id=:source
                UNION ALL
                SELECT payment_transaction_id,NULL::varchar,id
                  FROM payment_disputes WHERE provider_dispute_id=:source
                """).param("source", sourceId).query().listOfRows();
        if (rows.size() != 1) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new Owner((String) row.get("payment_id"),
                (String) row.get("refund_id"), (String) row.get("dispute_id")));
    }

    @Override
    public boolean post(Journal j) {
        try {
            transactions.executeWithoutResult(ignored -> jdbc.sql("""
                    CALL post_financial_journal(
                      CAST(:id AS varchar),CAST(:payment_id AS varchar),CAST(:refund_id AS varchar),
                      CAST(:dispute_id AS varchar),CAST(:journal_type AS varchar),
                      CAST(:balance_id AS varchar),CAST(:source_type AS varchar),
                      CAST(:source_id AS varchar),NULL,:amount_minor,CAST(:currency AS char(3)),
                      CAST(:occurred_at AS timestamptz),CAST(:observed_at AS timestamptz),
                      CAST(:lines AS jsonb))
                    """).param("id", j.id()).param("payment_id", j.paymentId())
                    .param("refund_id", j.refundId()).param("dispute_id", j.disputeId())
                    .param("journal_type", j.journalType()).param("balance_id", j.providerBalanceTransactionId())
                    .param("source_type", j.sourceType()).param("source_id", j.sourceId())
                    .param("amount_minor", j.amountMinor()).param("currency", j.currency())
                    .param("occurred_at", Timestamp.from(j.occurredAt()))
                    .param("observed_at", Timestamp.from(j.observedAt()))
                    .param("lines", j.linesJson()).update());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
