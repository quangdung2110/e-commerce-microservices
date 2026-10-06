package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import com.ecommerce.review.service.TakedownService;
import com.ecommerce.review.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-006 and WF-REVIEW-012: the official reply and the legal takedown. */
class AdminCommandIT extends AbstractReviewIT {

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ReviewQueryService reviewQueries;

    @Autowired
    private AdminReplyService adminReplies;

    @Autowired
    private TakedownService takedowns;

    private final UUID buyer = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID secondAdmin = UUID.randomUUID();

    private UUID approvedReview(String orderId) {
        UUID eligibilityId = grantEntitlement(orderId, "p-1", "sku-1", buyer);
        return reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();
    }

    /** PUT is an upsert; a later edit keeps the administrator who opened the reply. */
    @Test
    void replacingAReplyKeepsItsCreatorAndNotifiesOnlyOnce() {
        UUID reviewId = approvedReview("order-1");

        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));
        PublicDtos.OfficialReply replaced = adminReplies.putReply(secondAdmin, reviewId,
                new CommandDtos.PutAdminReplyRequest("Đã cập nhật"));

        assertThat(replaced.content()).isEqualTo("Đã cập nhật");
        var admins = jdbc.sql("""
                SELECT created_by_admin_id, updated_by_admin_id FROM review_admin_replies WHERE review_id = ?
                """).param(reviewId)
                .query((rs, n) -> java.util.List.of(
                        rs.getObject("created_by_admin_id", UUID.class),
                        rs.getObject("updated_by_admin_id", UUID.class)))
                .single();

        assertThat(admins.get(0)).isEqualTo(admin);
        assertThat(admins.get(1)).isEqualTo(secondAdmin);
        assertThat(outboxCount(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())).isEqualTo(1);
    }

    /** The reply appears on the public read, batch-loaded with the review. */
    @Test
    void theReplyIsVisibleOnThePublicRead() {
        UUID reviewId = approvedReview("order-2");
        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));

        var page = reviewQueries.listProductReviews("p-1", null, null, false, null, 20);

        assertThat(page.items()).singleElement()
                .satisfies(item -> assertThat(item.adminReply().content()).isEqualTo("Cảm ơn bạn"));
    }

    @Test
    void onlyAnApprovedReviewCanBeRepliedTo() {
        UUID reviewId = approvedReview("order-3");
        takedowns.takeDown(admin, reviewId, new CommandDtos.CreateTakedownRequest("CASE-1", "vi phạm"));

        assertThatThrownBy(() -> adminReplies.putReply(admin, reviewId,
                new CommandDtos.PutAdminReplyRequest("Xin lỗi")))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.REVIEW_NOT_REPLYABLE);
    }

    /** ADR-010: the audit record names the caller, and the body carries no actor field. */
    @Test
    void theTakedownRecordsTheActingAdministratorFromTrustedIdentity() {
        UUID reviewId = approvedReview("order-4");

        CommandDtos.TakedownReceipt receipt = takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-2", "vi phạm bản quyền"));

        assertThat(receipt.legalCaseId()).isEqualTo("CASE-2");
        assertThat(jdbc.sql("SELECT taken_down_by_admin_id FROM review_takedowns WHERE review_id = ?")
                .param(reviewId).query(UUID.class).single()).isEqualTo(admin);
        assertThat(jdbc.sql("SELECT status FROM reviews WHERE id = ?")
                .param(reviewId).query(String.class).single()).isEqualTo("REMOVED");
    }

    /** Neither the legal case, the reason nor the actor may reach an event. */
    @Test
    void neitherReviewEventCarriesLegalDataOrAdminIdentity() {
        UUID reviewId = approvedReview("order-5");
        takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-3", "lý do nội bộ tuyệt mật"));

        for (String type : java.util.List.of(
                ReviewEventType.REVIEW_REMOVED.type(),
                ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED.type())) {
            JsonNode envelope = outboxPayloads(type).get(0);
            assertThat(envelope.toString())
                    .doesNotContain("CASE-3", "lý do nội bộ tuyệt mật", admin.toString());
        }
    }

    @Test
    void theRemovalEventSaysWhetherTheReviewWasCounted() {
        UUID reviewId = approvedReview("order-6");
        takedowns.takeDown(admin, reviewId, new CommandDtos.CreateTakedownRequest("CASE-4", "vi phạm"));

        JsonNode envelope = outboxPayloads(ReviewEventType.REVIEW_REMOVED.type()).get(0);

        assertThat(ContractSchemas.validate(
                ReviewEventType.REVIEW_REMOVED.schemaFileName(), envelope)).isEmpty();
        assertThat(envelope.path("data").path("was_counted").asBoolean()).isTrue();
    }

    /** REMOVED is terminal: one record per review and no second takedown. */
    @Test
    void aReviewCanOnlyBeTakenDownOnce() {
        UUID reviewId = approvedReview("order-7");
        takedowns.takeDown(admin, reviewId, new CommandDtos.CreateTakedownRequest("CASE-5", "vi phạm"));

        assertThatThrownBy(() -> takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-6", "vi phạm lần hai")))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.TAKEDOWN_ALREADY_EXISTS);
        assertThat(jdbc.sql("SELECT count(*) FROM review_takedowns WHERE review_id = ?")
                .param(reviewId).query(Long.class).single()).isEqualTo(1);
    }

    /** A removed review leaves the public surface and stays out of it. */
    @Test
    void aRemovedReviewIsGoneFromThePublicList() {
        UUID reviewId = approvedReview("order-8");
        takedowns.takeDown(admin, reviewId, new CommandDtos.CreateTakedownRequest("CASE-7", "vi phạm"));

        assertThat(reviewQueries.listProductReviews("p-1", null, null, false, null, 20).items())
                .isEmpty();
        // The owner can still read their own review, but gets no signed media URL for it.
        assertThat(reviewQueries.getMyReview(buyer, reviewId).visibilityStatus().name())
                .isEqualTo("REMOVED");
    }
}
