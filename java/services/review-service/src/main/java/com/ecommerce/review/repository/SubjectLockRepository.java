package com.ecommerce.review.repository;

import com.ecommerce.review.privacy.SubjectKey;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The per-subject transaction lock of REVIEW-INV-012.
 *
 * <p>Every path that can persist a direct customer identifier takes this lock before it
 * checks the deletion guard and writes: the Order consumer, the customer
 * upload/create/edit/report commands, the privacy consumer and the notification branch
 * of an admin reply or takedown. Without it, a guard check and a write are two separate
 * moments and a late write can still slip past a privacy sweep.
 *
 * <p>It is transaction-scoped, so it releases on commit or rollback and never needs an
 * explicit unlock. It is also not a global lock: two different subjects never contend.
 */
@Repository
public class SubjectLockRepository {

    private final JdbcClient jdbc;

    public SubjectLockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Blocks until this transaction holds the subject lock.
     *
     * @throws IllegalStateException when called outside a transaction, where the lock
     *                               would be released immediately and guard nothing
     */
    public void lock(SubjectKey key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "the privacy subject lock is transaction-scoped and is meaningless outside a transaction");
        }
        // pg_advisory_xact_lock returns void, so the row is consumed and discarded rather
        // than mapped to a value type. The extractor still has to answer something:
        // JdbcClient rejects a null extractor result.
        jdbc.sql("SELECT pg_advisory_xact_lock(?)")
                .param(key.lockKey())
                .query((ResultSetExtractor<Boolean>) rs -> Boolean.TRUE);
    }
}
