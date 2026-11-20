package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.BalanceLedgerPort;
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
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            if (j.paymentId() != null)
                jdbc.sql("SELECT id FROM payment_transactions WHERE id=:id FOR UPDATE")
                        .param("id", j.paymentId()).query(String.class).single();
            var existing = jdbc.sql("""
                    SELECT id,journal_type,amount_minor,currency,payment_transaction_id,
                           refund_transaction_id,dispute_transaction_id,provider_balance_transaction_id
                      FROM financial_journals
                     WHERE (source_type=:source_type AND source_id=:source_id)
                        OR (CAST(:balance_id AS varchar) IS NOT NULL AND provider_balance_transaction_id=:balance_id)
                    """).param("source_type", j.sourceType()).param("source_id", j.sourceId())
                    .param("balance_id", j.providerBalanceTransactionId()).query().listOfRows();
            if (!existing.isEmpty()) {
                if (existing.size() != 1) throw new IllegalStateException("BALANCE_JOURNAL_CONFLICT");
                var prior = existing.getFirst();
                boolean same = j.journalType().equals(prior.get("journal_type"))
                        && j.amountMinor() == ((Number) prior.get("amount_minor")).longValue()
                        && j.currency().equals(prior.get("currency"))
                        && java.util.Objects.equals(j.paymentId(), prior.get("payment_transaction_id"))
                        && java.util.Objects.equals(j.refundId(), prior.get("refund_transaction_id"))
                        && java.util.Objects.equals(j.disputeId(), prior.get("dispute_transaction_id"))
                        && (j.providerBalanceTransactionId() == null || prior.get("provider_balance_transaction_id") == null
                            || java.util.Objects.equals(j.providerBalanceTransactionId(), prior.get("provider_balance_transaction_id")));
                boolean sameLines = jdbc.sql("""
                        SELECT jsonb_agg(jsonb_build_object('account_code',account_code,
                                   'direction',direction,'amount_minor',amount_minor,'currency',currency)
                                   ORDER BY line_no) =
                               (SELECT jsonb_agg(jsonb_build_object('account_code',value->>'account_code',
                                      'direction',value->>'direction','amount_minor',(value->>'amount_minor')::bigint,
                                      'currency',COALESCE(value->>'currency',:currency)) ORDER BY ordinality)
                                  FROM jsonb_array_elements(CAST(:lines AS jsonb)) WITH ORDINALITY)
                          FROM financial_ledger_entries WHERE journal_id=:id
                        """).param("id", prior.get("id")).param("lines", j.linesJson()).param("currency", j.currency()).query(Boolean.class).single();
                if (!same || !sameLines) throw new IllegalStateException("BALANCE_JOURNAL_CONFLICT");
                return false;
            }
            jdbc.sql("""
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
                    .param("lines", j.linesJson()).update();
            return true;
        }));
    }
}
