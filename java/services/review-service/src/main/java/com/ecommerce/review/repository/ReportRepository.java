package com.ecommerce.review.repository;

import com.ecommerce.review.domain.Report;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.domain.ReportStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@code review_reports}: community reports and their moderation outcome. */
@Repository
public class ReportRepository {

    private static final String COLUMNS = """
            id, review_id, reporter_user_id, reporter_erased_at, reason, detail, status,
            resolved_by_admin_id, resolved_at, created_at
            """;

    private static final RowMapper<Report> MAPPER = (rs, row) -> new Report(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "review_id"),
            Rows.uuid(rs, "reporter_user_id"),
            Rows.instant(rs, "reporter_erased_at"),
            Rows.enumValue(rs, "reason", ReportReason.class),
            rs.getString("detail"),
            Rows.enumValue(rs, "status", ReportStatus.class),
            Rows.uuid(rs, "resolved_by_admin_id"),
            Rows.instant(rs, "resolved_at"),
            Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one active report.
     *
     * <p>{@code uq_report_review_reporter_active_identity} excludes {@code DISMISSED},
     * so a caller whose earlier report an admin dismissed may report the review again,
     * while a duplicate active report is refused by the database rather than by a read
     * that could race (REVIEW-BR-008).
     *
     * @throws DuplicateKeyException when the caller already has an active report
     */
    public Report insert(UUID reviewId, UUID reporterUserId, ReportReason reason, String detail) {
        return jdbc.sql("""
                INSERT INTO review_reports (review_id, reporter_user_id, reason, detail, status)
                VALUES (?, ?, ?::report_reason, ?, 'PENDING')
                RETURNING
                """ + COLUMNS)
                .param(reviewId)
                .param(reporterUserId)
                .param(reason.name())
                .param(detail)
                .query(MAPPER).single();
    }

    /**
     * The automatic decision taken when the threshold hides a review: every
     * {@code PENDING} report becomes {@code ACTIONED} with a {@code resolved_at} and no
     * admin, because no admin judged it.
     */
    public int markPendingActioned(UUID reviewId, Instant now) {
        return jdbc.sql("""
                UPDATE review_reports
                   SET status = 'ACTIONED', resolved_at = ?
                 WHERE review_id = ? AND status = 'PENDING'
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(reviewId)
                .update();
    }

    /** The admin decision: every undismissed report of the review becomes DISMISSED. */
    public int dismissAll(UUID reviewId, UUID adminId, Instant now) {
        return jdbc.sql("""
                UPDATE review_reports
                   SET status = 'DISMISSED', resolved_by_admin_id = ?, resolved_at = ?
                 WHERE review_id = ? AND status <> 'DISMISSED'
                """)
                .param(adminId)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(reviewId)
                .update();
    }

    /** Recomputes {@code active_report_count} from the rows that are still not dismissed. */
    public int countActive(UUID reviewId) {
        return jdbc.sql("SELECT count(*) FROM review_reports WHERE review_id = ? AND status <> 'DISMISSED'")
                .param(reviewId).query(Integer.class).single();
    }

    /**
     * Batch loads the undismissed reports of a moderation page through
     * {@code ix_report_review_status}. Reporter identity is dropped by the mapper's
     * caller and never reaches a response.
     */
    public Map<UUID, List<Report>> loadUndismissedByReviewIds(Collection<UUID> reviewIds, int perReviewLimit) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        List<Report> rows = jdbc.sql("""
                SELECT id, review_id, reporter_user_id, reporter_erased_at, reason, detail,
                       status, resolved_by_admin_id, resolved_at, created_at
                  FROM review_reports
                 WHERE review_id IN (%s) AND status <> 'DISMISSED'
                 ORDER BY review_id, created_at
                """.formatted(Placeholders.of(reviewIds.size())))
                .params(List.copyOf(reviewIds))
                .query(MAPPER).list();
        return rows.stream()
                .collect(Collectors.groupingBy(Report::reviewId,
                        Collectors.collectingAndThen(Collectors.toList(),
                                list -> list.size() <= perReviewLimit ? list : list.subList(0, perReviewLimit))));
    }

    /**
     * The privacy hard purge. The lookup uses {@code ix_report_reporter_erasure}: the
     * unique index leads with {@code review_id} and cannot seek on the reporter alone.
     */
    public int purgeReporter(UUID subjectUserId, Instant now) {
        return jdbc.sql("""
                UPDATE review_reports
                   SET reporter_user_id = NULL, reporter_erased_at = ?
                 WHERE reporter_user_id = ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(subjectUserId)
                .update();
    }
}
