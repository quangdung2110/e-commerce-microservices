package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.FinancialOperationsQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public final class JdbcFinancialOperationsQueryAdapter implements FinancialOperationsQueryPort {
    private final JdbcClient jdbc;

    public JdbcFinancialOperationsQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Journal> findJournal(String journalId) {
        Optional<JournalHeader> header = jdbc.sql("""
                SELECT id,journal_type,amount_minor,currency,occurred_at
                  FROM financial_journals WHERE id=:id
                """).param("id", journalId).query((rs, ignored) -> new JournalHeader(
                        rs.getString("id"), rs.getString("journal_type"),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .optional();
        return header.map(value -> new Journal(value.id(), value.journalType(),
                value.amountMinor(), value.currency(), value.occurredAt(), lines(value.id())));
    }

    @Override
    public Optional<Dispute> findDispute(String disputeId) {
        return jdbc.sql("""
                SELECT id,payment_transaction_id,order_id,provider_dispute_id,amount_minor,
                       currency,reason_code,status,funds_state,evidence_due_at,opened_at,closed_at
                  FROM payment_disputes WHERE id=:id
                """).param("id", disputeId).query((rs, ignored) -> new Dispute(
                        rs.getString("id"), rs.getString("payment_transaction_id"),
                        rs.getLong("order_id"), rs.getString("provider_dispute_id"),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getString("reason_code"), rs.getString("status"),
                        rs.getString("funds_state"), instant(rs.getTimestamp("evidence_due_at")),
                        rs.getTimestamp("opened_at").toInstant(),
                        instant(rs.getTimestamp("closed_at")))).optional();
    }

    private List<Line> lines(String journalId) {
        return jdbc.sql("""
                SELECT line_no,account_code,direction,amount_minor,currency
                  FROM financial_ledger_entries WHERE journal_id=:id ORDER BY line_no
                """).param("id", journalId).query((rs, ignored) -> new Line(
                        rs.getShort("line_no"), rs.getString("account_code"),
                        rs.getString("direction"), rs.getLong("amount_minor"),
                        rs.getString("currency"))).list();
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record JournalHeader(String id, String journalType, long amountMinor,
                                 String currency, Instant occurredAt) {
    }
}
