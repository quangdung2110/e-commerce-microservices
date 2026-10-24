package com.ecommerce.review.integration;

import com.ecommerce.review.dto.Patch;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.messaging.UserDeletedPayload;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.PrivacyService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import com.ecommerce.review.service.TakedownService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F18-01: author edit history must survive moderation and privacy writes. */
class ContentEditedAtIT extends AbstractReviewIT {
    @Autowired private ReviewCommandService commands;
    @Autowired private ReviewQueryService queries;
    @Autowired private ModerationService moderation;
    @Autowired private AdminReplyService replies;
    @Autowired private TakedownService takedowns;
    @Autowired private PrivacyService privacy;

    @Test
    void onlyTheAcceptedAuthorPatchSetsTheTimestamp() {
        UUID author = UUID.randomUUID(), admin = UUID.randomUUID();
        UUID id = commands.create(author, new CommandDtos.CreateReviewRequest(
                grantEntitlement("edit-time", "p-1", "sku-1", author), 3, "Original", null)).review().id();
        assertThat(queries.getMyReview(author, id).contentEditedAt()).isNull();

        replies.putReply(admin, id, new CommandDtos.PutAdminReplyRequest("First official reply"));
        moderation.report(UUID.randomUUID(), id,
                new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
        moderation.dismissReports(admin, id);
        assertThat(queries.getMyReview(author, id).contentEditedAt()).isNull();

        var edited = commands.edit(author, id,
                new PatchReviewRequest(Patch.of(4), Patch.of("Author edit"), Patch.absent()));
        Instant timestamp = edited.review().contentEditedAt();
        assertThat(timestamp).isEqualTo(Instant.parse(
                outboxPayloads(ReviewEventType.REVIEW_UPDATED.type()).get(0).path("time").asText()));
        assertThat(queries.getMyReview(author, id).contentEditedAt()).isEqualTo(timestamp);
        assertThat(queries.listMyReviews(author, null, 20).items().get(0).contentEditedAt()).isEqualTo(timestamp);

        for (int i = 0; i < properties.reportThreshold(); i++) {
            moderation.report(UUID.randomUUID(), id,
                    new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
        }
        assertThat(queries.getMyReview(author, id).contentEditedAt()).isEqualTo(timestamp);
        moderation.dismissReports(admin, id);
        replies.putReply(admin, id, new CommandDtos.PutAdminReplyRequest("Updated official reply"));
        assertThatThrownBy(() -> commands.edit(author, id,
                new PatchReviewRequest(Patch.of(2), Patch.absent(), Patch.absent())))
                .isInstanceOf(com.ecommerce.review.common.ReviewException.class);
        takedowns.takeDown(admin, id, new CommandDtos.CreateTakedownRequest("QA-TIME", "QA only"));
        moderation.dismissReports(admin, id);
        var removed = queries.getMyReview(author, id);
        assertThat(removed.contentEditedAt()).isEqualTo(timestamp);
        assertThat(removed.editCount()).isEqualTo(1);
        assertThat(removed.updatedAt()).isAfter(timestamp);

        Instant deleted = Instant.now();
        privacy.applyUserDeleted(UUID.randomUUID(),
                new UserDeletedPayload(author, deleted, "GDPR_REQUEST", deleted.plus(90, ChronoUnit.DAYS)),
                properties.topics().auth(), 0, nextOffset());
        assertThat(jdbc.sql("SELECT content_edited_at FROM reviews WHERE id = ?").param(id)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(timestamp);
    }

    @Test
    void failedOwnerPatchDoesNotInventAnEditTime() {
        UUID author = UUID.randomUUID();
        UUID id = commands.create(author, new CommandDtos.CreateReviewRequest(
                grantEntitlement("failed-edit-time", "p-1", "sku-1", author), 3, "Original", null)).review().id();
        assertThatThrownBy(() -> commands.edit(UUID.randomUUID(), id,
                new PatchReviewRequest(Patch.of(4), Patch.absent(), Patch.absent())))
                .isInstanceOf(com.ecommerce.review.common.ReviewException.class);
        assertThat(queries.getMyReview(author, id).contentEditedAt()).isNull();
        assertThat(queries.getMyReview(author, id).editCount()).isZero();
    }
}
