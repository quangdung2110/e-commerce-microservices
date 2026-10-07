package com.ecommerce.review.repository;

import com.ecommerce.review.domain.PrivacyErasureStatus;
import com.ecommerce.review.domain.PrivacyRequest;
import com.ecommerce.review.privacy.SubjectKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code privacy_erasure_requests}: the deletion guard and the erasure lifecycle.
 *
 * <p>Existence of a row in <em>any</em> status is the guard. A request being
 * {@code FAILED} and retrying does not re-open the subject to new direct-identifier
 * writes ({@code docs/review/data.md 8}).
 */
@Repository
public class PrivacyRepository {

    private static final String COLUMNS = """
            id, source_event_id, subject_user_id, subject_match_key, subject_key_version,
            deletion_reason, deleted_at, hard_delete_after, status, anonymized_at, purged_at,
            failure_detail
            """;

    private static final RowMapper<PrivacyRequest> MAPPER = (rs, row) -> new PrivacyRequest(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "source_event_id"),
            Rows.uuid(rs, "subject_user_id"),
            rs.getBytes("subject_match_key"),
            rs.getShort("subject_key_version"),
            rs.getString("deletion_reason"),
            Rows.instant(rs, "deleted_at"),
            Rows.instant(rs, "hard_delete_after"),
            Rows.enumValue(rs, "status", PrivacyErasureStatus.class),
            Rows.instant(rs, "anonymized_at"),
            Rows.instant(rs, "purged_at"),
            rs.getString("failure_detail"));

    private final JdbcClient jdbc;

    public PrivacyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether a committed deletion guard exists for this subject.
     *
     * <p>Callers must already hold the subject lock: checking the guard without it
     * leaves exactly the race the lock exists to close (REVIEW-INV-012).
     */
    public boolean guardExists(SubjectKey key) {
        return jdbc.sql("SELECT count(*) FROM privacy_erasure_requests WHERE subject_match_key = ?")
                .param(key.bytes()).query(Long.class).single() > 0;
    }

    public Optional<PrivacyRequest> findBySubjectKey(SubjectKey key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM privacy_erasure_requests WHERE subject_match_key = ?")
                .param(key.bytes()).query(MAPPER).optional();
    }

    /**
     * Records the erasure request of a {@code USER_DELETED}.
     *
     * <p>{@code ON CONFLICT (subject_match_key) DO NOTHING} means a logically duplicate
     * deletion carrying a new CloudEvents id commits only its receipt and starts no
     * second lifecycle (WF-REVIEW-010 step 1).
     *
     * @return true when this call created the request
     */
    public boolean insertIfAbsent(UUID sourceEventId,
                                  UUID subjectUserId,
                                  SubjectKey key,
                                  String deletionReason,
                                  Instant deletedAt,
                                  Instant hardDeleteAfter) {
        return jdbc.sql("""
                INSERT INTO privacy_erasure_requests
                    (source_event_id, subject_user_id, subject_match_key, subject_key_version,
                     deletion_reason, deleted_at, hard_delete_after, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (subject_match_key) DO NOTHING
                """)
                .param(sourceEventId)
                .param(subjectUserId)
                .param(key.bytes())
                .param((int) key.version())
                .param(deletionReason)
                .param(OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC))
                .param(OffsetDateTime.ofInstant(hardDeleteAfter, ZoneOffset.UTC))
                .update() == 1;
    }

    /**
     * Closes the anonymise step. {@code anonymized_at} is written with the status
     * because {@code ck_privacy_status_timestamps_consistent} requires both, and because
     * a later {@code PURGED} is only accepted once it is set.
     */
    public void markAnonymized(SubjectKey key, Instant now) {
        jdbc.sql("""
                UPDATE privacy_erasure_requests
                   SET status = 'ANONYMIZED', anonymized_at = ?, failure_detail = NULL
                 WHERE subject_match_key = ? AND status IN ('RECEIVED', 'FAILED')
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(key.bytes())
                .update();
    }

    /**
     * Completes the hard purge of the request row itself.
     *
     * <p>{@code subject_user_id} and {@code purged_at} are written in one statement:
     * {@code ck_privacy_purge_identity_consistent} rejects clearing the identifier on
     * its own, and that rejection is deterministic, so the critical-severity retry would
     * reproduce it until the legal deadline passed (WF-REVIEW-010 step 4). The HMAC
     * guard is deliberately retained.
     */
    public void markPurged(SubjectKey key, Instant now) {
        jdbc.sql("""
                UPDATE privacy_erasure_requests
                   SET subject_user_id = NULL, purged_at = ?, status = 'PURGED'
                 WHERE subject_match_key = ? AND status = 'ANONYMIZED'
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(key.bytes())
                .update();
    }

    /** Records a failure without ever dropping the guard or silently dead-lettering. */
    public void markFailed(SubjectKey key, String failureDetail) {
        jdbc.sql("""
                UPDATE privacy_erasure_requests
                   SET status = 'FAILED', failure_detail = ?
                 WHERE subject_match_key = ? AND status <> 'PURGED'
                """)
                .param(failureDetail)
                .param(key.bytes())
                .update();
    }

    /** The purge queue, read through {@code ix_privacy_hard_delete_sweep}. */
    public List<PrivacyRequest> findDueForPurge(Instant now, int batchSize) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM privacy_erasure_requests
                 WHERE status = 'ANONYMIZED' AND hard_delete_after <= ?
                 ORDER BY hard_delete_after
                 LIMIT ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(batchSize)
                .query(MAPPER).list();
    }

    /** Feeds {@code review_privacy_hard_delete_overdue_total}, a critical alert. */
    public long countOverdue(Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM privacy_erasure_requests
                 WHERE status <> 'PURGED' AND hard_delete_after <= ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(Long.class).single();
    }
}
