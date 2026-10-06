package com.ecommerce.review.integration;

import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.dto.AdminDtos;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.service.MediaAssetService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one deliberate exception to "no signed URL for media of a review that is not
 * {@code APPROVED}".
 *
 * <p>A report about an image or a video cannot be adjudicated without seeing it, so the
 * moderation surface issues a URL even for a hidden review — scoped to
 * {@code /api/v1/admin/**} and with a strictly shorter TTL. Public and owner reads still
 * issue nothing for that same media ({@code docs/review/interfaces.md 2}).
 */
class AdminMediaIT extends AbstractReviewIT {

    private static final byte[] IMAGE = "admin-media-fixture-bytes".getBytes();

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ReviewQueryService reviewQueries;

    @Autowired
    private ModerationService moderation;

    @Autowired
    private MediaAssetService mediaAssetService;

    @Autowired
    private com.ecommerce.review.service.TakedownService takedowns;

    private final UUID author = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private UUID reviewWithMedia(String orderId) {
        UUID eligibilityId = grantEntitlement(orderId, "p-1", "sku-1", author);
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(author,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg",
                        (long) IMAGE.length, null));
        upload(issued, IMAGE);
        return reviewCommands.create(author, new CommandDtos.CreateReviewRequest(
                eligibilityId, 4, "Có ảnh", List.of(issued.assetId()))).review().id();
    }

    private void report(UUID reviewId) {
        moderation.report(UUID.randomUUID(), reviewId,
                new CommandDtos.CreateReviewReportRequest(ReportReason.OFFENSIVE, null));
    }

    /** Without this, a report about an image would be unjudgeable. */
    @Test
    void theModerationSurfaceSignsMediaOfAHiddenReview() {
        UUID reviewId = reviewWithMedia("order-1");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId);
        }

        AdminDtos.AdminModerationCase moderationCase = moderation
                .listQueue(ModerationQueueStatus.HIDDEN_BY_REPORT, null, 50).items().get(0);

        assertThat(moderationCase.review().visibilityStatus().name()).isEqualTo("HIDDEN_BY_REPORT");
        assertThat(moderationCase.review().media()).singleElement().satisfies(media -> {
            assertThat(media.url()).startsWith("http");
            assertThat(media.mediaType()).isEqualTo(MediaType.IMAGE);
        });
    }

    /** The same media, on the two surfaces that must not expose it. */
    @Test
    void publicAndOwnerReadsIssueNoUrlForThatSameMedia() {
        UUID reviewId = reviewWithMedia("order-2");
        for (int i = 0; i < properties.reportThreshold(); i++) {
            report(reviewId);
        }

        assertThat(reviewQueries.listProductReviews("p-1", null, null, false, null, 20).items())
                .isEmpty();

        PublicDtos.OwnerReview owner = reviewQueries.getMyReview(author, reviewId);
        assertThat(owner.visibilityStatus().name()).isEqualTo("HIDDEN_BY_REPORT");
        assertThat(owner.hasMedia()).isTrue();
        // The attachment is still there — a dismissal has to be able to restore it — but
        // no URL is minted for it outside the admin surface.
        assertThat(owner.media()).isEmpty();
    }

    /**
     * A review with reports below the threshold is still {@code APPROVED}, so it appears
     * on both surfaces at once: the only place the two TTLs can be compared directly.
     */
    @Test
    void theAdminUrlExpiresBeforeThePublicOne() {
        UUID reviewId = reviewWithMedia("order-3");
        report(reviewId);

        PublicDtos.PublicReviewMedia publicMedia = reviewQueries
                .listProductReviews("p-1", null, null, false, null, 20)
                .items().get(0).media().get(0);
        AdminDtos.AdminReviewMedia adminMedia = moderation
                .listQueue(ModerationQueueStatus.REPORTED, null, 50)
                .items().get(0).review().media().get(0);

        assertThat(adminMedia.assetId()).isEqualTo(publicMedia.assetId());
        assertThat(adminMedia.urlExpiresAt()).isBefore(publicMedia.urlExpiresAt());
        // The two expiries are computed on separate requests, so the gap is the TTL
        // difference plus however long the second call took; a second of slack keeps the
        // assertion about the TTLs rather than about scheduling.
        Duration gap = Duration.between(adminMedia.urlExpiresAt(), publicMedia.urlExpiresAt());
        Duration expected = properties.media().publicUrlTtl().minus(properties.media().adminUrlTtl());
        assertThat(gap).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    /** Startup refuses a configuration where the exception would outlive the rule. */
    @Test
    void theConfiguredAdminTtlIsShorterThanThePublicOne() {
        assertThat(properties.media().adminUrlTtl())
                .isLessThan(properties.media().publicUrlTtl());
    }

    /**
     * A takedown detaches the media, so nothing is left to sign anywhere — the admin
     * exception exists to judge a report, not to keep removed content reachable.
     */
    @Test
    void aRemovedReviewHasNoMediaLeftOnAnySurface() {
        UUID reviewId = reviewWithMedia("order-4");
        report(reviewId);
        takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-1", "vi phạm bản quyền"));

        assertThat(jdbc.sql("SELECT count(*) FROM review_media WHERE review_id = ?")
                .param(reviewId).query(Long.class).single()).isZero();
        assertThat(reviewQueries.getMyReview(author, reviewId).media()).isEmpty();
        assertThat(moderation.listQueue(ModerationQueueStatus.REPORTED, null, 50).items()).isEmpty();
        assertThat(moderation.listQueue(ModerationQueueStatus.HIDDEN_BY_REPORT, null, 50).items())
                .isEmpty();
    }
}
