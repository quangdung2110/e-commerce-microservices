package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.messaging.InboundEvents;
import com.ecommerce.review.messaging.UserDeletedPayload;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.privacy.IdentityHmac;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.PrivacyService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.TakedownService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-010 and REVIEW-INV-012. */
class PrivacyIT extends AbstractReviewIT {

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private AdminReplyService adminReplies;

    @Autowired
    private TakedownService takedowns;

    @Autowired
    private IdentityHmac identityHmac;

    @Autowired
    private InboundEvents inboundEvents;

    private final UUID subject = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    /**
     * @param hardDeleteAfter the legal deadline; the deletion itself is dated far enough
     *                        back that a past deadline is still consistent with
     *                        ck_privacy_hard_delete_after_deletion
     */
    private void deleteUser(UUID userId, Instant hardDeleteAfter) {
        Instant deletedAt = hardDeleteAfter.minus(30, ChronoUnit.DAYS);
        privacyService.applyUserDeleted(UUID.randomUUID(),
                new UserDeletedPayload(userId, deletedAt, "GDPR_REQUEST", hardDeleteAfter),
                properties.topics().auth(), 0, nextOffset());
    }

    @Test
    void deletionAnonymisesTheAuthorWithoutTouchingRatingOrPublishingAnything() {
        UUID eligibilityId = grantEntitlement("order-1", "p-1", "sku-1", subject);
        UUID reviewId = reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null)).review().id();

        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        var row = jdbc.sql("""
                SELECT author_display_name, rating, author_anonymized_at IS NOT NULL AS anonymized
                  FROM reviews WHERE id = ?
                """).param(reviewId)
                .query((rs, n) -> java.util.List.of(
                        rs.getString("author_display_name"), rs.getInt("rating"),
                        rs.getBoolean("anonymized")))
                .single();

        assertThat(row.get(0)).isEqualTo("Khách hàng ẩn danh");
        assertThat(row.get(1)).isEqualTo(5);
        assertThat(row.get(2)).isEqualTo(true);
        // No Review event and no metric snapshot: every PII-owning service gets
        // USER_DELETED straight from Auth.
        assertThat(outboxTypes()).containsExactly(ReviewEventType.REVIEW_CREATED.type());
    }

    /** A replayed event is a receipt no-op. */
    @Test
    void replayingTheSameDeletionEventChangesNothing() {
        UUID eventId = UUID.randomUUID();
        long offset = nextOffset();
        UserDeletedPayload payload = new UserDeletedPayload(subject,
                Instant.now().minus(1, ChronoUnit.DAYS), "GDPR_REQUEST",
                Instant.now().plus(30, ChronoUnit.DAYS));

        privacyService.applyUserDeleted(eventId, payload, properties.topics().auth(), 0, offset);
        privacyService.applyUserDeleted(eventId, payload, properties.topics().auth(), 0, offset);

        assertThat(privacyRequestCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM review_event_receipts WHERE consumer_name = 'privacy-sync'")
                .query(Long.class).single()).isEqualTo(1);
    }

    /** A logically duplicate deletion with a new event id keeps exactly one guard. */
    @Test
    void aSecondDeletionOfTheSameSubjectKeepsOneGuard() {
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));
        deleteUser(subject, Instant.now().plus(60, ChronoUnit.DAYS));

        assertThat(privacyRequestCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM review_event_receipts WHERE consumer_name = 'privacy-sync'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aGuardedAccountCannotCreateAReview() {
        UUID eligibilityId = grantEntitlement("order-2", "p-1", "sku-1", subject);
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        assertThatThrownBy(() -> reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null)))
                .isInstanceOf(ReviewException.class)
                // Generic authorization failure: no ACCOUNT_DELETED oracle.
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** REVIEW-BR-001: a guarded subject's late order commits its receipt and nothing else. */
    @Test
    void aGuardedSubjectsLateOrderCreatesNoEntitlement() {
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        entitlements.apply(UUID.randomUUID(),
                new com.ecommerce.review.messaging.OrderCompletedPayload("order-3", subject, Instant.now(),
                        java.util.List.of(new com.ecommerce.review.messaging.OrderCompletedPayload.Item(
                                "p-1", "sku-1", "Đen / L", null, null, 1))),
                properties.topics().order(), 0, nextOffset());

        assertThat(jdbc.sql("SELECT count(*) FROM review_eligibilities").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM review_event_receipts WHERE consumer_name = 'order-sync'")
                .query(Long.class).single()).isEqualTo(1);
    }

    /**
     * A guard does not reject an administrator: the reply is still recorded, only the
     * notification outbox row carrying the recipient identifier is suppressed.
     */
    @Test
    void aGuardedOwnerStillGetsTheReplyButNoNotificationRequest() {
        UUID eligibilityId = grantEntitlement("order-4", "p-1", "sku-1", subject);
        UUID reviewId = reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));

        assertThat(jdbc.sql("SELECT content FROM review_admin_replies WHERE review_id = ?")
                .param(reviewId).query(String.class).single()).isEqualTo("Cảm ơn bạn");
        // The raw owner id is still present inside the retention window; that is not
        // permission to notify.
        assertThat(jdbc.sql("SELECT owner_user_id FROM review_eligibilities WHERE id = ?")
                .param(eligibilityId).query(UUID.class).optional()).isPresent();
        assertThat(outboxCount(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())).isZero();
    }

    @Test
    void aGuardedOwnerStillGetsTheTakedownButNoRemovalNotification() {
        UUID eligibilityId = grantEntitlement("order-5", "p-1", "sku-1", subject);
        UUID reviewId = reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-1", "vi phạm bản quyền"));

        assertThat(jdbc.sql("SELECT status FROM reviews WHERE id = ?")
                .param(reviewId).query(String.class).single()).isEqualTo("REMOVED");
        assertThat(outboxCount(ReviewEventType.REVIEW_REMOVED.type())).isEqualTo(1);
        assertThat(outboxCount(ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED.type())).isZero();
    }

    /** An unguarded owner does get the notification, with three identifiers and no more. */
    @Test
    void anUnguardedOwnerGetsAReplyNotificationCarryingOnlyIdentifiers() {
        UUID buyer = UUID.randomUUID();
        UUID eligibilityId = grantEntitlement("order-6", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();

        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));
        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Đã cập nhật"));

        // Only the first creation notifies; a replacement never notifies again.
        assertThat(outboxCount(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())).isEqualTo(1);
        var data = outboxPayloads(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())
                .get(0).path("data");
        assertThat(data.path("recipient_user_id").asText()).isEqualTo(buyer.toString());
        assertThat(data.has("content")).isFalse();
        assertThat(data.size()).isEqualTo(3);
    }

    /**
     * The hard purge nulls every direct identifier and keeps the HMAC guard, so a late or
     * redriven event cannot write the UUID back in.
     */
    @Test
    void theHardPurgeClearsIdentifiersAndKeepsTheGuard() {
        UUID eligibilityId = grantEntitlement("order-7", "p-1", "sku-1", subject);
        reviewCommands.create(subject, new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null));
        deleteUser(subject, Instant.now().minus(1, ChronoUnit.DAYS));

        int purged = privacyService.purgeDue(10);

        assertThat(purged).isEqualTo(1);
        var request = jdbc.sql("""
                SELECT subject_user_id, purged_at, anonymized_at, status, octet_length(subject_match_key) AS key_len
                  FROM privacy_erasure_requests
                """).query((rs, n) -> java.util.Arrays.asList(
                        rs.getObject("subject_user_id"),
                        rs.getObject("purged_at"),
                        rs.getObject("anonymized_at"),
                        rs.getString("status"),
                        rs.getInt("key_len")))
                .single();

        assertThat(request.get(0)).isNull();
        assertThat(request.get(1)).isNotNull();
        assertThat(request.get(2)).isNotNull();
        assertThat(request.get(3)).isEqualTo("PURGED");
        assertThat(request.get(4)).isEqualTo(32);

        assertThat(jdbc.sql("""
                SELECT owner_user_id IS NULL AND owner_erased_at IS NOT NULL
                  FROM review_eligibilities WHERE id = ?
                """).param(eligibilityId).query(Boolean.class).single()).isTrue();
        // The review, its rating and its content survive the purge.
        assertThat(jdbc.sql("SELECT count(*) FROM reviews").query(Long.class).single()).isEqualTo(1);
    }

    /**
     * The paired CHECKs are what make a half-written purge impossible. Clearing an
     * identifier without its {@code *_erased_at} is rejected, so the failure is loud and
     * deterministic rather than a silently incomplete erasure.
     */
    @Test
    void clearingAnIdentifierWithoutItsErasureTimestampIsRejected() {
        UUID eligibilityId = grantEntitlement("order-8", "p-1", "sku-1", subject);

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE review_eligibilities SET owner_user_id = NULL WHERE id = ?
                """).param(eligibilityId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** A purged subject stays guarded, so a redelivered order still creates nothing. */
    @Test
    void aRedrivenOrderEventAfterThePurgeStillCreatesNoEntitlement() {
        deleteUser(subject, Instant.now().minus(1, ChronoUnit.DAYS));
        privacyService.purgeDue(10);

        entitlements.apply(UUID.randomUUID(),
                new com.ecommerce.review.messaging.OrderCompletedPayload("order-9", subject, Instant.now(),
                        java.util.List.of(new com.ecommerce.review.messaging.OrderCompletedPayload.Item(
                                "p-1", "sku-1", null, null, null, 1))),
                properties.topics().order(), 0, nextOffset());

        assertThat(jdbc.sql("SELECT count(*) FROM review_eligibilities").query(Long.class).single())
                .isZero();
    }

    /**
     * The CloudEvents id is what makes the consumer idempotent and is persisted as a UUID
     * in both the receipt and {@code source_event_id}. A non-UUID id therefore cannot be
     * processed safely at all: it is a contract failure, not a transient one, and it must
     * fail before any transaction opens rather than be coerced into something storable.
     */
    @Test
    void aNonUuidCloudEventsIdIsRejectedAsAContractViolation() {
        String envelope = """
                {"specversion":"1.0","id":"not-a-uuid",
                 "type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth",
                 "subject":"accounts/%s","time":"2026-09-09T10:15:30Z",
                 "datacontenttype":"application/json",
                 "dataschema":"/contracts/events/auth/v1/schemas/user-deleted.schema.json",
                 "data":{"user_id":"%s","deleted_at":"2026-09-09T10:15:30Z",
                         "reason":"GDPR_REQUEST","hard_delete_after":"2026-10-09T10:15:30Z"}}
                """.formatted(subject, subject);

        assertThatThrownBy(() -> inboundEvents.requireEventId(inboundEvents.parse(envelope)))
                .isInstanceOf(InboundEvents.ContractViolationException.class)
                .hasMessageContaining("not a UUID");

        assertThat(privacyRequestCount()).isZero();
    }

    /**
     * Auth publishes {@code USER_DELETED} directly after commit with at most three
     * retries and no outbox, so the event can be lost before Review ever sees it. Review
     * cannot detect that absence — there is no synchronous Auth lookup — so the recovery
     * is an Auth redrive, and a redriven event has to start the lifecycle normally even
     * though it arrives long after the deletion.
     */
    @Test
    void aRedrivenDeletionEventArrivingLateStillStartsTheLifecycle() {
        UUID eligibilityId = grantEntitlement("order-10", "p-1", "sku-1", subject);
        reviewCommands.create(subject,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null));

        // Nothing exists until the event arrives: the absence itself is invisible here.
        assertThat(privacyRequestCount()).isZero();

        // Truncated to microseconds up front: timestamptz stores that precision and
        // rounds, so a nanosecond fixture would be compared against a value the database
        // legitimately rounded rather than the one it was given.
        Instant deletedLongAgo = Instant.now().minus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        privacyService.applyUserDeleted(UUID.randomUUID(),
                new UserDeletedPayload(subject, deletedLongAgo, "GDPR_REQUEST",
                        deletedLongAgo.plus(30, ChronoUnit.DAYS)),
                properties.topics().auth(), 0, nextOffset());

        assertThat(privacyRequestCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM privacy_erasure_requests")
                .query(String.class).single()).isEqualTo("ANONYMIZED");
        assertThat(jdbc.sql("SELECT author_display_name FROM reviews").query(String.class).single())
                .isEqualTo("Khách hàng ẩn danh");
        // The deadline is still measured from the original deletion, not from the redrive.
        assertThat(jdbc.sql("SELECT hard_delete_after FROM privacy_erasure_requests")
                .query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(deletedLongAgo.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void theGuardKeyIsTheHmacOfTheSubject() {
        deleteUser(subject, Instant.now().plus(30, ChronoUnit.DAYS));

        byte[] stored = jdbc.sql("SELECT subject_match_key FROM privacy_erasure_requests")
                .query(byte[].class).single();

        assertThat(stored).isEqualTo(identityHmac.subjectKey(subject).bytes());
    }

    private long privacyRequestCount() {
        return jdbc.sql("SELECT count(*) FROM privacy_erasure_requests").query(Long.class).single();
    }
}
