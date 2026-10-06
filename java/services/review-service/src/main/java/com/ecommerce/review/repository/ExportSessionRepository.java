package com.ecommerce.review.repository;

import com.ecommerce.review.domain.ExportSession;
import com.ecommerce.review.domain.ExportSessionStatus;
import com.ecommerce.review.domain.PartitionOffset;
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
 * {@code rating_export_sessions} and {@code rating_export_session_offsets}.
 *
 * <p>The offsets are captured once and every page of the session echoes exactly them,
 * which is REVIEW-INV-009 and what lets Search page without Review holding a database
 * transaction across HTTP calls (ADR-006).
 */
@Repository
public class ExportSessionRepository {

    private static final RowMapper<ExportSession> SESSION_MAPPER = (rs, row) -> new ExportSession(
            Rows.uuid(rs, "id"),
            rs.getString("requested_by"),
            Rows.enumValue(rs, "status", ExportSessionStatus.class),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "expires_at"));

    private static final RowMapper<PartitionOffset> OFFSET_MAPPER = (rs, row) -> new PartitionOffset(
            rs.getString("topic"),
            rs.getInt("partition"),
            rs.getLong("replay_from_offset"));

    private final JdbcClient jdbc;

    public ExportSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ExportSession createSession(String requestedBy, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO rating_export_sessions (requested_by, status, expires_at)
                VALUES (?, 'ACTIVE', ?)
                RETURNING id, requested_by, status, created_at, expires_at
                """)
                .param(requestedBy)
                .param(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(SESSION_MAPPER).single();
    }

    public void saveOffsets(UUID sessionId, List<PartitionOffset> offsets) {
        for (PartitionOffset offset : offsets) {
            jdbc.sql("""
                    INSERT INTO rating_export_session_offsets
                        (session_id, topic, partition, replay_from_offset)
                    VALUES (?, ?, ?, ?)
                    """)
                    .param(sessionId)
                    .param(offset.topic())
                    .param(offset.partition())
                    .param(offset.replayFromOffset())
                    .update();
        }
    }

    public Optional<ExportSession> findSession(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, requested_by, status, created_at, expires_at
                  FROM rating_export_sessions WHERE id = ?
                """).param(sessionId).query(SESSION_MAPPER).optional();
    }

    public List<PartitionOffset> findOffsets(UUID sessionId) {
        return jdbc.sql("""
                SELECT topic, partition, replay_from_offset
                  FROM rating_export_session_offsets
                 WHERE session_id = ?
                 ORDER BY topic, partition
                """).param(sessionId).query(OFFSET_MAPPER).list();
    }

    /**
     * WF-REVIEW-011: expires sessions past their TTL so an old watermark cannot be
     * paged against forever. A Search run that meets the expiry discards the whole run.
     */
    public int expireSessions(Instant now) {
        return jdbc.sql("""
                UPDATE rating_export_sessions
                   SET status = 'EXPIRED'
                 WHERE status = 'ACTIVE' AND expires_at <= ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM rating_export_sessions WHERE status = 'ACTIVE'")
                .query(Long.class).single();
    }
}
