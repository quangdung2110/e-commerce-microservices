package com.ecommerce.review.repository;

import com.ecommerce.review.domain.AdminReply;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@code review_admin_replies}: at most one official reply per review. */
@Repository
public class AdminReplyRepository {

    private static final String COLUMNS = """
            id, review_id, created_by_admin_id, updated_by_admin_id, content, created_at, updated_at
            """;

    private static final RowMapper<AdminReply> MAPPER = (rs, row) -> new AdminReply(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "review_id"),
            Rows.uuid(rs, "created_by_admin_id"),
            Rows.uuid(rs, "updated_by_admin_id"),
            rs.getString("content"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public AdminReplyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One upsert result: the stored reply, and whether this call created it. */
    public record UpsertResult(AdminReply reply, boolean created) {
    }

    /**
     * Creates or replaces the single official reply.
     *
     * <p>{@code created_by_admin_id} is preserved on conflict, so a later edit by
     * another administrator does not rewrite who opened the reply. Only the creating
     * branch may produce a notification request; a replacement never notifies again
     * (WF-REVIEW-006).
     */
    public UpsertResult upsert(UUID reviewId, UUID adminId, String content) {
        // xmax is zero only on the tuple an INSERT produced, so the upsert itself
        // reports which branch ran. Comparing timestamps instead would misread an
        // edit made inside the same transaction as a creation.
        return jdbc.sql("""
                INSERT INTO review_admin_replies
                    (review_id, created_by_admin_id, updated_by_admin_id, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (review_id) DO UPDATE
                    SET content = EXCLUDED.content,
                        updated_by_admin_id = EXCLUDED.updated_by_admin_id
                RETURNING
                """ + COLUMNS + ", (xmax = 0) AS inserted")
                .param(reviewId).param(adminId).param(adminId).param(content)
                .query((rs, row) -> new UpsertResult(MAPPER.mapRow(rs, row), rs.getBoolean("inserted")))
                .single();
    }

    public Optional<AdminReply> findByReviewId(UUID reviewId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_admin_replies WHERE review_id = ?")
                .param(reviewId).query(MAPPER).optional();
    }

    /** Batch loads the replies of a page of reviews; the public read shows the content. */
    public Map<UUID, AdminReply> loadByReviewIds(Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_admin_replies WHERE review_id IN ("
                        + Placeholders.of(reviewIds.size()) + ")")
                .params(List.copyOf(reviewIds))
                .query(MAPPER).list().stream()
                .collect(Collectors.toMap(AdminReply::reviewId, Function.identity()));
    }
}
