package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.Patch;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import com.ecommerce.review.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-003 and WF-REVIEW-004. */
class ReviewCommandIT extends AbstractReviewIT {

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ReviewQueryService reviewQueries;

    private final UUID buyer = UUID.randomUUID();

    @Test
    void creatingAReviewClaimsItsEntitlementAndWritesOneOutboxRow() {
        UUID eligibilityId = grantEntitlement("order-1", "p-1", "sku-1", buyer);

        ReviewCommandService.CommandResult result = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Rất tốt", null));

        assertThat(result.review().status().name()).isEqualTo("APPROVED");
        assertThat(result.review().editCount()).isZero();
        assertThat(result.variantName()).isEqualTo("Đen / L");
        assertThat(eligibilityStatus(eligibilityId).name()).isEqualTo("CLAIMED");
        assertThat(outboxTypes()).containsExactly(ReviewEventType.REVIEW_CREATED.type());
    }

    /** ADR-003: the stored row is the wire message, so it must satisfy its own schema. */
    @Test
    void theStoredEnvelopeValidatesAgainstItsPublishedSchema() {
        UUID eligibilityId = grantEntitlement("order-2", "p-1", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 4, null, null));

        JsonNode envelope = outboxPayloads(ReviewEventType.REVIEW_CREATED.type()).get(0);

        assertThat(ContractSchemas.validate(
                ReviewEventType.REVIEW_CREATED.schemaFileName(), envelope)).isEmpty();
        assertThat(envelope.path("source").asText()).isEqualTo("/services/review");
        assertThat(envelope.path("data").path("product_id").asText()).isEqualTo("p-1");
    }

    /** ck_outbox_columns_mirror_payload keeps the routing columns and the envelope in step. */
    @Test
    void theOutboxColumnsMirrorTheStoredEnvelope() {
        UUID eligibilityId = grantEntitlement("order-3", "p-1", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 3, null, null));

        var row = jdbc.sql("""
                SELECT event_id::text AS event_id, event_type, aggregate_type, aggregate_id,
                       partition_key, payload::text AS payload, created_at
                  FROM review_outbox
                """).query((rs, n) -> List.of(
                        rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                        rs.getString("partition_key"), rs.getString("payload")))
                .single();
        JsonNode envelope = readTree(row.get(5));

        assertThat(row.get(0)).isEqualTo(envelope.path("id").asText());
        assertThat(row.get(1)).isEqualTo(envelope.path("type").asText());
        assertThat(row.get(2)).isEqualTo("REVIEW");
        assertThat(row.get(3)).isEqualTo(envelope.path("data").path("review_id").asText());
        assertThat(row.get(4)).isEqualTo("p-1");
    }

    /** The envelope time must equal the row's created_at, which is the transaction now(). */
    @Test
    void theEnvelopeTimeIsTheTransactionTimestamp() {
        UUID eligibilityId = grantEntitlement("order-4", "p-1", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 3, null, null));

        Instant createdAt = jdbc.sql("SELECT created_at FROM review_outbox")
                .query(java.time.OffsetDateTime.class).single().toInstant();
        Instant envelopeTime = Instant.parse(
                outboxPayloads(ReviewEventType.REVIEW_CREATED.type()).get(0).path("time").asText());

        assertThat(envelopeTime).isEqualTo(createdAt);
    }

    @Test
    void aSecondCreateIsRejectedAsAlreadyReviewed() {
        UUID eligibilityId = grantEntitlement("order-5", "p-1", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null));

        assertThatThrownBy(() -> reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, null, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.ALREADY_REVIEWED);
    }

    @Test
    void anotherAccountCannotClaimTheEntitlement() {
        UUID eligibilityId = grantEntitlement("order-6", "p-1", "sku-1", buyer);

        assertThatThrownBy(() -> reviewCommands.create(UUID.randomUUID(),
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.NOT_ELIGIBILITY_OWNER);
    }

    @Test
    void anExpiredEntitlementCannotBeClaimed() {
        UUID eligibilityId = grantEntitlement("order-7", "p-1", "sku-1", buyer,
                Instant.now().minus(90, ChronoUnit.DAYS), null);

        assertThatThrownBy(() -> reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.ELIGIBILITY_EXPIRED);
    }

    @Test
    void anUnknownEntitlementIsNotFound() {
        assertThatThrownBy(() -> reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(UUID.randomUUID(), 5, null, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.ELIGIBILITY_NOT_FOUND);
    }

    /** The author pseudonym is derived, never supplied, and contains no profile data. */
    @Test
    void theAuthorNameIsADerivedPseudonym() {
        UUID eligibilityId = grantEntitlement("order-8", "p-1", "sku-1", buyer);
        var created = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null));

        assertThat(created.review().authorDisplayName()).matches("Khách hàng #[A-Z2-7]{12}");
    }

    @Test
    void exactlyOneEditIsPermitted() {
        UUID eligibilityId = grantEntitlement("order-9", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 3, "Tạm được", null)).review().id();

        var edited = reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.of(5), Patch.absent(), Patch.absent()));
        assertThat(edited.review().rating()).isEqualTo(5);
        assertThat(edited.review().editCount()).isEqualTo(1);
        // Content was absent from the patch, so it survives untouched.
        assertThat(edited.review().content()).isEqualTo("Tạm được");

        assertThatThrownBy(() -> reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.of(4), Patch.absent(), Patch.absent())))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.EDIT_QUOTA_EXHAUSTED);
    }

    /** ADR-004: a content-only edit is not an aggregate change. */
    @Test
    void aContentOnlyEditIsMarkedAsNotAffectingTheAggregate() {
        UUID eligibilityId = grantEntitlement("order-10", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Ban đầu", null)).review().id();

        reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.absent(), Patch.of("Đã sửa"), Patch.absent()));

        JsonNode data = outboxPayloads(ReviewEventType.REVIEW_UPDATED.type()).get(0).path("data");
        assertThat(data.path("affects_aggregate").asBoolean()).isFalse();
        assertThat(data.path("old_rating").asInt()).isEqualTo(4);
        assertThat(data.path("new_rating").asInt()).isEqualTo(4);
    }

    @Test
    void anExplicitNullContentClearsTheText() {
        UUID eligibilityId = grantEntitlement("order-11", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Có nội dung", null)).review().id();

        var edited = reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.absent(), Patch.of(null), Patch.absent()));

        assertThat(edited.review().content()).isNull();
    }

    @Test
    void onlyTheOwnerCanEdit() {
        UUID eligibilityId = grantEntitlement("order-12", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, null, null)).review().id();

        assertThatThrownBy(() -> reviewCommands.edit(UUID.randomUUID(), reviewId,
                new PatchReviewRequest(Patch.of(1), Patch.absent(), Patch.absent())))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.NOT_REVIEW_OWNER);
    }

    /** The recovery reads that make a lost response resolvable without a blind retry. */
    @Test
    void theRecoveryReadsResolveALostResponse() {
        UUID eligibilityId = grantEntitlement("order-13", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null)).review().id();

        PublicDtos.MyEligibilityReviewStatus status =
                reviewQueries.getMyEligibilityStatus(buyer, eligibilityId);
        assertThat(status.status().name()).isEqualTo("CLAIMED");
        assertThat(status.reviewId()).isEqualTo(reviewId);

        PublicDtos.OwnerReview owner = reviewQueries.getMyReview(buyer, reviewId);
        assertThat(owner.reviewId()).isEqualTo(reviewId);
        assertThat(owner.editCount()).isZero();
        assertThat(owner.variantName()).isEqualTo("Đen / L");
    }

    /** The buyer's pending list and own review carry the product the entitlement snapshotted. */
    @Test
    void theOwnerSurfacesCarryTheProductSnapshot() {
        UUID eligibilityId = grantEntitlement("order-20", "p-1", "sku-1", buyer, Instant.now(), "Đen / L",
                "Áo thun Terra", "http://localhost:8088/media/p-1/main.jpg");

        PublicDtos.PendingEligibility pending = reviewQueries.listPending(buyer, null, 20).items().get(0);
        assertThat(pending.orderId()).isEqualTo("order-20");
        assertThat(pending.productName()).isEqualTo("Áo thun Terra");
        assertThat(pending.productImageUrl()).isEqualTo("http://localhost:8088/media/p-1/main.jpg");

        PublicDtos.OwnerReview created = reviewQueries.toOwnerReview(reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null)));
        assertThat(created.productName()).isEqualTo("Áo thun Terra");
        assertThat(created.productImageUrl()).isEqualTo("http://localhost:8088/media/p-1/main.jpg");
        assertThat(reviewQueries.getMyReview(buyer, created.reviewId()).productName())
                .isEqualTo("Áo thun Terra");
    }

    /** The owner list is newest first, keyset-paged, private to the caller and keeps non-public reviews. */
    @Test
    void myReviewsListsOnlyMyOwnNewestFirstAndPages() {
        UUID other = UUID.randomUUID();
        UUID first = reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(
                grantEntitlement("order-21", "p-1", "sku-1", buyer), 4, "Một", null)).review().id();
        UUID second = reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(
                grantEntitlement("order-22", "p-2", "sku-2", buyer), 5, "Hai", null)).review().id();
        reviewCommands.create(other, new CommandDtos.CreateReviewRequest(
                grantEntitlement("order-23", "p-1", "sku-1", other), 1, "Người khác", null));
        jdbc.sql("UPDATE reviews SET status = 'REMOVED' WHERE id = ?").param(second).update();

        PublicDtos.OwnerReviewPage page = reviewQueries.listMyReviews(buyer, null, 1);
        assertThat(page.items()).extracting(PublicDtos.OwnerReview::reviewId).containsExactly(second);
        assertThat(page.items().get(0).visibilityStatus().name()).isEqualTo("REMOVED");
        assertThat(page.items().get(0).media()).isEmpty();
        assertThat(page.nextCursor()).isNotNull();

        PublicDtos.OwnerReviewPage next = reviewQueries.listMyReviews(buyer, page.nextCursor(), 1);
        assertThat(next.items()).extracting(PublicDtos.OwnerReview::reviewId).containsExactly(first);
        // A full page still carries a cursor, as on every other keyset list; the follow-up is empty.
        PublicDtos.OwnerReviewPage last = reviewQueries.listMyReviews(buyer, next.nextCursor(), 1);
        assertThat(last.items()).isEmpty();
        assertThat(last.nextCursor()).isNull();

        assertThat(reviewQueries.listMyReviews(UUID.randomUUID(), null, 20).items()).isEmpty();
    }

    /** The public list only ever shows approved reviews of the requested product. */
    @Test
    void thePublicListShowsTheApprovedReview() {
        UUID eligibilityId = grantEntitlement("order-14", "p-1", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Tốt", null));

        PublicDtos.PublicReviewPage page =
                reviewQueries.listProductReviews("p-1", null, null, false, null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).content()).isEqualTo("Tốt");
        assertThat(page.items().get(0).media()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    /** The composite foreign key refuses a review whose product does not match its entitlement. */
    @Test
    void aReviewCannotCarryAProductItsEntitlementDoesNotHave() {
        UUID eligibilityId = grantEntitlement("order-15", "p-1", "sku-1", buyer);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO reviews (eligibility_id, product_id, sku_id, author_display_name, rating)
                VALUES (?, 'p-other', 'sku-1', 'Khách hàng #AAAAAAAAAAAA', 5)
                """).param(eligibilityId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void aRatingOutsideOneToFiveIsRefusedByTheDatabase() {
        UUID eligibilityId = grantEntitlement("order-16", "p-1", "sku-1", buyer);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO reviews (eligibility_id, product_id, sku_id, author_display_name, rating)
                VALUES (?, 'p-1', 'sku-1', 'Khách hàng #AAAAAAAAAAAA', 6)
                """).param(eligibilityId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
