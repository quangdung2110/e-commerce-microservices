package com.ecommerce.review.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The transaction timestamp.
 *
 * <p>Every command needs one instant that is simultaneously the CloudEvents
 * {@code time} of its outbox rows and their {@code created_at}, because contract tests
 * assert the two are equal ({@code docs/review/data.md 9}). PostgreSQL {@code now()} is
 * {@code transaction_timestamp()}, so reading it once per transaction gives a value that
 * is stable for the whole unit of work and has the microsecond precision the column
 * stores, rather than a JVM clock reading that would round differently.
 */
@Component
public class TransactionClock {

    private final JdbcClient jdbc;

    public TransactionClock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.sql("SELECT now()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
}
