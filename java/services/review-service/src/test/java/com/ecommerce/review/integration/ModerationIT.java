package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.dto.AdminDtos;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-007: report, the automatic hide, and the administrator's dismissal. */
class ModerationIT extends AbstractReviewIT {

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ReviewQueryService reviewQueries;

    @Autowired
    private ModerationService moderation;

    private final UUID author = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private UUID approvedReview(String orderId) {
        UUID eligibilityId = grantEntitlement(orderId, "p-1", "sku-1", author);
        return reviewCommands.create(author,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();
    }

    private void report(UUID reviewId, UUID reporter) {
        moderation.report(reporter, reviewId,
                new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
    }

    @Test
    void theAuthorCannotReportTheirOwnReview() {
        UUID reviewId = approvedReview("order-1");

        assertThatThrownBy(() -> report(reviewId, author))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.REVIEW_REPORT_FORBIDDEN);
    }

    /** One active report per account; the partial unique index enforces it. */
    @Test
    void oneAccountCannotReportTheSameReviewTwice() {
        UUID reviewId = approvedReview("order-2");
        UUID reporter = UUID.randomUUID();
        report(reviewId, reporter);

        assertThatThrownBy(() -> report(reviewId, reporter))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.ALREADY_REPORTED);
    }

    /** The first report of a cycle stamps first_reported_at, the queue sort key. */
    @Test
    void theFirstReportOpensAModerationCycle() {
        UUID reviewId = approvedReview("order-3");
        report(reviewId, UUID.randomUUID());

        assertThat(jdbc.sql("SELECT first_reported_at FROM reviews WHERE id = ?")
                .param(reviewId).query(java.time.OffsetDateTime.class).optional()).isPresent();
        assertThat(outboxTypes()).containsExactly(ReviewEventType.REVIEW_CREATED.type());
    }

    @Test
    void reachingTheThresholdHidesTheReviewExactlyOnce() {
        UUID reviewId = approvedReview("order-4");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }

        assertThat(reviewStatus(reviewId)).isEqualTo(ReviewStatus.HIDDEN_BY_REPORT);
        assertThat(outboxCount(ReviewEventType.REVIEW_HIDDEN.type())).isEqualTo(1);
        // Hiding is the automatic decision, so every pending report becomes ACTIONED
        // with a resolved_at and no administrator.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM review_reports
                 WHERE review_id = ? AND status = 'ACTIONED' AND resolved_by_admin_id IS NULL
                """).param(reviewId).query(Long.class).single())
                .isEqualTo(properties.reportThreshold());
    }

    /** A hidden review is gone from the public surface immediately. */
    @Test
    void aHiddenReviewLeavesThePublicList() {
        UUID reviewId = approvedReview("order-5");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }

        assertThat(reviewQueries.listProductReviews("p-1", null, null, false, null, 20).items())
                .isEmpty();
    }

    /**
     * The queue is keyed on the review: three reports on one review are one case, not
     * three rows, and reporter identity never appears.
     */
    @Test
    void theQueueShowsOneCasePerReviewAndNoReporterIdentity() {
        UUID reviewId = approvedReview("order-6");
        report(reviewId, UUID.randomUUID());
        report(reviewId, UUID.randomUUID());
        report(reviewId, UUID.randomUUID());

        AdminDtos.AdminModerationCasePage page =
                moderation.listQueue(ModerationQueueStatus.REPORTED, null, 50);

        assertThat(page.items()).hasSize(1);
        AdminDtos.AdminModerationCase moderationCase = page.items().get(0);
        assertThat(moderationCase.review().reviewId()).isEqualTo(reviewId);
        assertThat(moderationCase.review().activeReportCount()).isEqualTo(3);
        assertThat(moderationCase.reports()).hasSize(3);
        assertThat(objectMapper.valueToTree(moderationCase).toString())
                .doesNotContain("reporter_user_id");
    }

    /**
     * Hiding moves every report to {@code ACTIONED}, so a queue keyed on report state
     * would lose the case exactly when it needs a decision. It moves between the two
     * review states instead, keeping the same sort key.
     */
    @Test
    void aCaseMovesFromReportedToHiddenWithoutLeavingTheQueue() {
        UUID reviewId = approvedReview("order-7");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }

        assertThat(moderation.listQueue(ModerationQueueStatus.REPORTED, null, 50).items()).isEmpty();
        assertThat(moderation.listQueue(ModerationQueueStatus.HIDDEN_BY_REPORT, null, 50).items())
                .singleElement()
                .satisfies(item -> assertThat(item.review().reviewId()).isEqualTo(reviewId));
    }

    /** Closing the HIDDEN_BY_REPORT -> APPROVED edge end to end. */
    @Test
    void dismissingRestoresAHiddenReviewAndPublishesReviewApproved() {
        UUID reviewId = approvedReview("order-8");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }

        AdminDtos.AdminModerationResult result = moderation.dismissReports(admin, reviewId);

        assertThat(result.visibilityStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(result.dismissedReportCount()).isEqualTo(properties.reportThreshold());
        assertThat(result.activeReportCount()).isZero();
        assertThat(outboxCount(ReviewEventType.REVIEW_APPROVED.type())).isEqualTo(1);
        assertThat(reviewQueries.listProductReviews("p-1", null, null, false, null, 20).items())
                .hasSize(1);
    }

    /**
     * The restore keeps its history: {@code first_reported_at} and {@code hidden_at}
     * survive, and every index over them is partial, so the retained values are inert.
     */
    @Test
    void theRestoreKeepsTheModerationHistoryAndANewReportReopensTheCycle() {
        UUID reviewId = approvedReview("order-9");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }
        moderation.dismissReports(admin, reviewId);

        var afterRestore = timestamps(reviewId);
        assertThat(afterRestore.get(0)).isNotNull();
        assertThat(afterRestore.get(1)).isNotNull();

        report(reviewId, UUID.randomUUID());

        var afterNewReport = timestamps(reviewId);
        assertThat(afterNewReport.get(0)).isAfterOrEqualTo(afterRestore.get(0));
        assertThat(reviewStatus(reviewId)).isEqualTo(ReviewStatus.APPROVED);
        // Dismissed reports cannot hide the review a second time.
        assertThat(jdbc.sql("SELECT active_report_count FROM reviews WHERE id = ?")
                .param(reviewId).query(Integer.class).single()).isEqualTo(1);
    }

    /** A review that is not APPROVED is simply not there, as far as reporting is concerned. */
    @Test
    void aHiddenReviewCannotBeReportedAndDoesNotRevealThatItIsHidden() {
        UUID reviewId = approvedReview("order-10");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId, UUID.randomUUID());
        }

        assertThatThrownBy(() -> report(reviewId, UUID.randomUUID()))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
    }

    /** Dismissing a review with nothing to dismiss still answers its current state. */
    @Test
    void dismissingIsIdempotentByOutcome() {
        UUID reviewId = approvedReview("order-11");

        AdminDtos.AdminModerationResult result = moderation.dismissReports(admin, reviewId);

        assertThat(result.visibilityStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(result.dismissedReportCount()).isZero();
        assertThat(outboxCount(ReviewEventType.REVIEW_APPROVED.type())).isZero();
    }

    private ReviewStatus reviewStatus(UUID reviewId) {
        return ReviewStatus.valueOf(jdbc.sql("SELECT status FROM reviews WHERE id = ?")
                .param(reviewId).query(String.class).single());
    }

    private java.util.List<java.time.OffsetDateTime> timestamps(UUID reviewId) {
        return jdbc.sql("SELECT first_reported_at, hidden_at FROM reviews WHERE id = ?")
                .param(reviewId)
                .query((rs, n) -> java.util.Arrays.asList(
                        rs.getObject("first_reported_at", java.time.OffsetDateTime.class),
                        rs.getObject("hidden_at", java.time.OffsetDateTime.class)))
                .single();
    }
}
