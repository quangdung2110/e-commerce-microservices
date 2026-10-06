package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.RatingDelta;
import com.ecommerce.review.dto.InternalDtos;
import com.ecommerce.review.repository.RatingAggregateRepository;
import com.ecommerce.review.service.RatingExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-009 and ADR-006: one frozen watermark for the whole export run. */
class RatingExportIT extends AbstractReviewIT {

    @Autowired
    private RatingExportService ratingExports;

    @Autowired
    private RatingAggregateRepository aggregates;

    @Test
    void creatingASessionPersistsTheWatermarkOfEveryPartition() {
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");

        assertThat(session.replayFromOffsets()).isNotEmpty();
        assertThat(session.replayFromOffsets())
                .allSatisfy(offset -> {
                    assertThat(offset.topic()).isEqualTo(properties.topics().review());
                    assertThat(offset.offset()).isGreaterThanOrEqualTo(0);
                });
        assertThat(jdbc.sql("SELECT count(*) FROM rating_export_session_offsets WHERE session_id = ?")
                .param(session.sessionId()).query(Long.class).single())
                .isEqualTo(session.replayFromOffsets().size());
    }

    /**
     * REVIEW-INV-009: every page echoes the same offsets, even when the scan sees a
     * mutation newer than the watermark. That overlap is what {@code review_revision}
     * deduplicates on the Search side.
     */
    @Test
    void everyPageEchoesTheSameOffsetsEvenAfterAMutation() {
        aggregates.applyDelta("p-1", RatingDelta.ofReview(5, false, 1));
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");

        InternalDtos.RatingAggregateExportPage first = ratingExports.page(session.sessionId(), null, 500);
        aggregates.applyDelta("p-2", RatingDelta.ofReview(3, true, 1));
        InternalDtos.RatingAggregateExportPage second = ratingExports.page(session.sessionId(), null, 500);

        assertThat(first.replayFromOffsets()).isEqualTo(session.replayFromOffsets());
        assertThat(second.replayFromOffsets()).isEqualTo(session.replayFromOffsets());
        assertThat(first.sessionId()).isEqualTo(session.sessionId());
        // The later page legitimately sees the newer product; the watermark did not move.
        assertThat(second.items()).hasSize(2);
    }

    @Test
    void aPageIsKeysetOrderedByProductId() {
        aggregates.applyDelta("p-a", RatingDelta.ofReview(5, false, 1));
        aggregates.applyDelta("p-b", RatingDelta.ofReview(4, false, 1));
        aggregates.applyDelta("p-c", RatingDelta.ofReview(3, false, 1));
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");

        InternalDtos.RatingAggregateExportPage first = ratingExports.page(session.sessionId(), null, 2);
        assertThat(first.items()).extracting(InternalDtos.RatingAggregateSnapshot::productId)
                .containsExactly("p-a", "p-b");
        assertThat(first.nextAfterProductId()).isEqualTo("p-b");

        InternalDtos.RatingAggregateExportPage second =
                ratingExports.page(session.sessionId(), first.nextAfterProductId(), 2);
        assertThat(second.items()).extracting(InternalDtos.RatingAggregateSnapshot::productId)
                .containsExactly("p-c");
        assertThat(second.nextAfterProductId()).isNull();
    }

    @Test
    void aSnapshotCarriesTheRevisionSearchGuardsOn() {
        aggregates.applyDelta("p-1", RatingDelta.ofReview(5, true, 1));
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");

        InternalDtos.RatingAggregateSnapshot snapshot =
                ratingExports.page(session.sessionId(), null, 500).items().get(0);

        assertThat(snapshot.reviewRevision()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.totalReviews()).isEqualTo(1);
        assertThat(snapshot.totalWithMedia()).isEqualTo(1);
        assertThat(snapshot.observedAt()).isNotNull();
    }

    @Test
    void anUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> ratingExports.page(UUID.randomUUID(), null, 500))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.EXPORT_SESSION_NOT_FOUND);
    }

    /** An expired session invalidates the whole run, so it is 410 and not an empty page. */
    @Test
    void anExpiredSessionIsGone() {
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");
        // Both timestamps move back together: ck_export_session_expiry_after_creation
        // still has to hold for a session that has simply run out of time.
        jdbc.sql("""
                UPDATE rating_export_sessions
                   SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                 WHERE id = ?
                """).param(session.sessionId()).update();

        assertThatThrownBy(() -> ratingExports.page(session.sessionId(), null, 500))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.EXPORT_SESSION_EXPIRED);
    }

    /** Deleting a session takes its offsets with it. */
    @Test
    void offsetsAreRemovedWithTheirSession() {
        InternalDtos.RatingAggregateExportSession session = ratingExports.createSession("search-service");

        jdbc.sql("DELETE FROM rating_export_sessions WHERE id = ?").param(session.sessionId()).update();

        assertThat(jdbc.sql("SELECT count(*) FROM rating_export_session_offsets WHERE session_id = ?")
                .param(session.sessionId()).query(Long.class).single()).isZero();
    }
}
