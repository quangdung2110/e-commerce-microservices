package com.ecommerce.order.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Immutable attempt↔transaction mapping and per-transaction financial version (data.md §3.5). */
@Repository
public class PaymentAttemptStore {

    public record AttemptRow(long orderId, int attemptNumber, String transactionId, long amountMinor,
                             String currency, String terminalStatus, String terminalReasonCode,
                             long lastFinancialStateVersion, UUID operationId, Instant createdAt,
                             Instant resolvedAt) {
    }

    private static final RowMapper<AttemptRow> ATTEMPT = (rs, i) -> new AttemptRow(rs.getLong("order_id"),
            rs.getInt("payment_attempt_number"), rs.getString("payment_transaction_id"), rs.getLong("amount_minor"),
            rs.getString("currency"), rs.getString("terminal_status"), rs.getString("terminal_reason_code"),
            rs.getLong("last_financial_state_version"), Db.uuid(rs, "operation_id"), Db.instant(rs, "created_at"),
            Db.instant(rs, "resolved_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentAttemptStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Binds the transaction Payment returned; a replay of the same binding is a no-op. */
    public void bind(long orderId, int attemptNumber, String transactionId, long amountMinor, UUID operationId) {
        jdbc.update("""
                INSERT INTO order_payment_attempts (order_id, payment_attempt_number, payment_transaction_id,
                    amount_minor, operation_id)
                VALUES (:order, :attempt, :txn, :amount, :operation)
                ON CONFLICT (order_id, payment_attempt_number) DO NOTHING
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("attempt", attemptNumber)
                .addValue("txn", transactionId).addValue("amount", amountMinor).addValue("operation", operationId));
    }

    public Optional<AttemptRow> find(long orderId, int attemptNumber) {
        return jdbc.query("SELECT * FROM order_payment_attempts WHERE order_id = :order AND payment_attempt_number = :n",
                Map.of("order", orderId, "n", attemptNumber), ATTEMPT).stream().findFirst();
    }

    public Optional<AttemptRow> findByTransaction(String transactionId) {
        return jdbc.query("SELECT * FROM order_payment_attempts WHERE payment_transaction_id = :txn",
                Map.of("txn", transactionId), ATTEMPT).stream().findFirst();
    }

    public Optional<AttemptRow> lockByTransaction(String transactionId) {
        return jdbc.query("SELECT * FROM order_payment_attempts WHERE payment_transaction_id = :txn FOR UPDATE",
                Map.of("txn", transactionId), ATTEMPT).stream().findFirst();
    }

    public List<AttemptRow> list(long orderId) {
        return jdbc.query("SELECT * FROM order_payment_attempts WHERE order_id = :order ORDER BY payment_attempt_number",
                Map.of("order", orderId), ATTEMPT);
    }

    /** Sets the terminal status once; later facts never overwrite it. */
    public boolean markTerminal(String transactionId, String terminalStatus, String reasonCode, Instant at) {
        return jdbc.update("""
                UPDATE order_payment_attempts SET terminal_status = :status, terminal_reason_code = :reason,
                    resolved_at = :at
                WHERE payment_transaction_id = :txn AND terminal_status IS NULL
                """, new MapSqlParameterSource().addValue("txn", transactionId).addValue("status", terminalStatus)
                .addValue("reason", reasonCode).addValue("at", Db.ts(at))) == 1;
    }

    /** Advances the per-transaction financial high-watermark; false when stale or equal. */
    public boolean advanceVersion(String transactionId, long version) {
        return jdbc.update("""
                UPDATE order_payment_attempts SET last_financial_state_version = :version
                WHERE payment_transaction_id = :txn AND last_financial_state_version < :version
                """, Map.of("txn", transactionId, "version", version)) == 1;
    }
}
