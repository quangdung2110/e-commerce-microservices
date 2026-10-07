package com.ecommerce.order.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Checkout submission snapshot and preparation state (data.md §3.9a, ADR-020). */
@Repository
public class SubmissionStore {

    public record SubmissionRow(UUID checkoutToken, UUID userId, String requestDigest, String requestJson,
                                String state, Long orderId, String failureJson, Instant createdAt,
                                Instant updatedAt) {
    }

    private static final RowMapper<SubmissionRow> ROW = (rs, i) -> new SubmissionRow(Db.uuid(rs, "checkout_token"),
            Db.uuid(rs, "user_id"), rs.getString("request_digest"), rs.getString("request"), rs.getString("state"),
            Db.nullableLong(rs, "order_id"), rs.getString("failure"), Db.instant(rs, "created_at"),
            Db.instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public SubmissionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true when this call created the submission. */
    public boolean insertIfAbsent(UUID token, UUID userId, String digest, String requestJson) {
        return jdbc.update("""
                INSERT INTO order_checkout_submissions (checkout_token, user_id, request_digest, request, state)
                VALUES (:token, :user, :digest, CAST(:request AS jsonb), 'PREPARING')
                ON CONFLICT (checkout_token) DO NOTHING
                """, new MapSqlParameterSource().addValue("token", token).addValue("user", userId)
                .addValue("digest", digest).addValue("request", requestJson)) == 1;
    }

    public Optional<SubmissionRow> find(UUID token) {
        return jdbc.query("SELECT * FROM order_checkout_submissions WHERE checkout_token = :token",
                Map.of("token", token), ROW).stream().findFirst();
    }

    public Optional<SubmissionRow> lock(UUID token) {
        return jdbc.query("SELECT * FROM order_checkout_submissions WHERE checkout_token = :token FOR UPDATE",
                Map.of("token", token), ROW).stream().findFirst();
    }

    public void attachOrder(UUID token, long orderId) {
        jdbc.update("UPDATE order_checkout_submissions SET order_id = :order, updated_at = NOW() WHERE checkout_token = :token",
                Map.of("order", orderId, "token", token));
    }

    /** PREPARING is the only non-terminal state; terminal states never change. */
    public boolean transition(UUID token, String state, String failureJson) {
        return jdbc.update("""
                UPDATE order_checkout_submissions SET state = :state, failure = CAST(:failure AS jsonb),
                    updated_at = NOW()
                WHERE checkout_token = :token AND state = 'PREPARING'
                """, new MapSqlParameterSource().addValue("token", token).addValue("state", state)
                .addValue("failure", failureJson)) == 1;
    }
}
