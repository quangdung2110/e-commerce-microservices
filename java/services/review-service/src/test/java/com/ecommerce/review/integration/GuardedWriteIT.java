package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.Patch;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.messaging.UserDeletedPayload;
import com.ecommerce.review.service.MediaAssetService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.PrivacyService;
import com.ecommerce.review.service.ReviewCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every customer write path rejects a subject that carries a committed deletion guard,
 * and rejects it with the generic authorization failure (WF-REVIEW-010).
 *
 * <p>The uniform answer is the point. A dedicated code, or a different code per path,
 * would turn the API into an oracle for which accounts have been deleted.
 */
class GuardedWriteIT extends AbstractReviewIT {

    private static final byte[] IMAGE = "guarded-write-fixture-bytes".getBytes();

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private MediaAssetService mediaAssetService;

    @Autowired
    private ModerationService moderation;

    @Autowired
    private PrivacyService privacyService;

    private final UUID subject = UUID.randomUUID();

    private void guard(UUID userId) {
        privacyService.applyUserDeleted(UUID.randomUUID(),
                new UserDeletedPayload(userId, Instant.now().minus(1, ChronoUnit.DAYS), "GDPR_REQUEST",
                        Instant.now().plus(30, ChronoUnit.DAYS)),
                properties.topics().auth(), 0, nextOffset());
    }

    @Test
    void aGuardedCallerCannotIssueAnUploadInstruction() {
        guard(subject);

        assertThatThrownBy(() -> mediaAssetService.issueUploadInstruction(subject,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // Nothing was written, so the guard also stops the object key being minted.
        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets").query(Long.class).single())
                .isZero();
    }

    /**
     * An asset issued before the deletion cannot be attached afterwards: the guard is
     * checked on the write, not on the upload.
     */
    @Test
    void aGuardedCallerCannotAttachAnAssetItAlreadyOwns() {
        UUID eligibilityId = grantEntitlement("order-1", "p-1", "sku-1", subject);
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(subject,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg",
                        (long) IMAGE.length, null));
        upload(issued, IMAGE);
        guard(subject);

        assertThatThrownBy(() -> reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, List.of(issued.assetId()))))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(jdbc.sql("SELECT upload_state FROM review_media_assets WHERE id = ?")
                .param(issued.assetId()).query(String.class).single()).isEqualTo("ISSUED");
    }

    @Test
    void aGuardedCallerCannotEditTheirOwnReview() {
        UUID eligibilityId = grantEntitlement("order-2", "p-1", "sku-1", subject);
        UUID reviewId = reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 3, "Ban đầu", null)).review().id();
        guard(subject);

        assertThatThrownBy(() -> reviewCommands.edit(subject, reviewId,
                new PatchReviewRequest(Patch.of(5), Patch.absent(), Patch.absent())))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // The edit quota is untouched: a rejected command spends nothing.
        assertThat(jdbc.sql("SELECT edit_count FROM reviews WHERE id = ?")
                .param(reviewId).query(Integer.class).single()).isZero();
    }

    @Test
    void aGuardedCallerCannotReport() {
        UUID authorEligibility = grantEntitlement("order-3", "p-1", "sku-1", UUID.randomUUID());
        UUID authorId = jdbc.sql("SELECT owner_user_id FROM review_eligibilities WHERE id = ?")
                .param(authorEligibility).query(UUID.class).single();
        UUID reviewId = reviewCommands.create(authorId,
                new CommandDtos.CreateReviewRequest(authorEligibility, 4, "Nội dung", null)).review().id();
        guard(subject);

        assertThatThrownBy(() -> moderation.report(subject, reviewId,
                new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(jdbc.sql("SELECT count(*) FROM review_reports").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT active_report_count FROM reviews WHERE id = ?")
                .param(reviewId).query(Integer.class).single()).isZero();
    }

    /**
     * The rejection carries no hint that the account was deleted: it is the same
     * {@code FORBIDDEN} an ordinary authorization failure produces.
     */
    @Test
    void theRejectionNeverNamesTheDeletion() {
        guard(subject);

        ReviewException rejected = null;
        try {
            mediaAssetService.issueUploadInstruction(subject,
                    new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));
        } catch (ReviewException e) {
            rejected = e;
        }

        assertThat(rejected).isNotNull();
        assertThat(rejected.getMessage().toLowerCase())
                .doesNotContain("delete", "deleted", "erasure", "privacy", "guard");
    }
}
