package com.ecommerce.review.repository;

import com.ecommerce.review.domain.AttachedMedia;
import com.ecommerce.review.domain.MediaType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code review_media}: which assets a review currently shows, and in what order.
 *
 * <p>{@code asset_id UNIQUE} is what stops one upload being reused across reviews, and
 * {@code (review_id, sort_order) UNIQUE} keeps the display order well defined.
 */
@Repository
public class ReviewMediaRepository {

    private static final RowMapper<AttachedMedia> MAPPER = (rs, row) -> new AttachedMedia(
            Rows.uuid(rs, "review_id"),
            Rows.uuid(rs, "asset_id"),
            Rows.enumValue(rs, "media_type", MediaType.class),
            rs.getString("object_key"),
            rs.getInt("sort_order"));

    private final JdbcClient jdbc;

    public ReviewMediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void attach(UUID reviewId, UUID assetId, int sortOrder) {
        jdbc.sql("INSERT INTO review_media (review_id, asset_id, sort_order) VALUES (?, ?, ?)")
                .param(reviewId).param(assetId).param(sortOrder).update();
    }

    /**
     * Removes every current link of a review and answers which assets it detached.
     *
     * <p>The caller then queues exactly those assets for cleanup with the reason that
     * detached them, in the same transaction (WF-REVIEW-002).
     */
    public List<UUID> detachAll(UUID reviewId) {
        return jdbc.sql("DELETE FROM review_media WHERE review_id = ? RETURNING asset_id")
                .param(reviewId).query(UUID.class).list();
    }

    public List<UUID> attachedAssetIds(UUID reviewId) {
        return jdbc.sql("SELECT asset_id FROM review_media WHERE review_id = ? ORDER BY sort_order")
                .param(reviewId).query(UUID.class).list();
    }

    /**
     * Batch loads the media of a page of reviews.
     *
     * <p>One query per page, never one per review: the public list, the owner read and
     * the moderation queue all page through {@code ix_media_review}
     * ({@code docs/review/data.md 10}).
     */
    public Map<UUID, List<AttachedMedia>> loadByReviewIds(Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        List<AttachedMedia> rows = jdbc.sql("""
                SELECT m.review_id, m.asset_id, m.sort_order, a.media_type, a.object_key
                  FROM review_media m
                  JOIN review_media_assets a ON a.id = m.asset_id
                 WHERE m.review_id IN (%s)
                   AND a.upload_state = 'ATTACHED'
                 ORDER BY m.review_id, m.sort_order
                """.formatted(Placeholders.of(reviewIds.size())))
                .params(List.copyOf(reviewIds))
                .query(MAPPER).list();
        return rows.stream().collect(Collectors.groupingBy(AttachedMedia::reviewId));
    }
}
