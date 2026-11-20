package com.ecommerce.review.integration;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.Patch;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.service.MediaCleanupService;
import com.ecommerce.review.service.MediaAssetService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.TakedownService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WF-REVIEW-002 and ADR-011: issue, verify at attach time, detach, delete. */
class MediaAssetIT extends AbstractReviewIT {

    private static final byte[] IMAGE = "not-really-a-jpeg-but-bytes".getBytes();

    @Autowired
    private MediaAssetService mediaAssetService;

    @Autowired
    private MediaAssetRepository mediaAssets;

    @Autowired
    private MediaCleanupService mediaCleanup;

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private TakedownService takedowns;

    private final UUID buyer = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private UUID issueAndUpload(UUID owner) {
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(owner,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg",
                        (long) IMAGE.length, null));
        upload(issued, IMAGE);
        return issued.assetId();
    }

    /**
     * The response is an asset id, an expiry and a signed instruction, and nothing else.
     *
     * <p>The contract exposes no {@code object_key} field, so a client can neither choose
     * a key nor address an object outside the one signature it was handed. The key is
     * also unguessable random material that carries no user identifier, which is what
     * keeps it useless as an identity oracle ({@code docs/review/data.md 4}).
     */
    @Test
    void theUploadResponseCarriesNoObjectKeyFieldAndNoIdentity() {
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));

        String objectKey = jdbc.sql("SELECT object_key FROM review_media_assets WHERE id = ?")
                .param(issued.assetId()).query(String.class).single();

        assertThat(objectMapper.valueToTree(issued).toString()).doesNotContain("object_key");
        assertThat(objectKey).doesNotContain(buyer.toString());
        assertThat(objectKey).doesNotContain(issued.assetId().toString());
        assertThat(issued.upload().method()).isEqualTo("PUT");
        assertThat(issued.expiresAt()).isAfter(Instant.now());
        assertThat(assetState(issued.assetId())).isEqualTo("ISSUED");
    }

    /**
     * The instruction pins a no-overwrite upload.
     *
     * <p>Without it, bytes could be replaced between the moment Review verified the
     * object and the moment it attached it, which would make the whole verification step
     * decorative (ADR-011). Review signs the condition; enforcing it is the storage
     * server's half of the contract.
     */
    @Test
    void theUploadInstructionIsSingleWrite() {
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));

        assertThat(issued.upload().headers()).containsEntry("If-None-Match", "*");
        assertThat(issued.upload().headers()).containsEntry("Content-Type", "image/jpeg");
    }

    @Test
    void creatingAReviewVerifiesAndAttachesTheAssetWithoutAnyClientVerifyCall() {
        UUID eligibilityId = grantEntitlement("order-1", "p-1", "sku-1", buyer);
        UUID assetId = issueAndUpload(buyer);

        var result = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Có ảnh", List.of(assetId)));

        assertThat(result.review().hasMedia()).isTrue();
        assertThat(assetState(assetId)).isEqualTo("ATTACHED");
        assertThat(jdbc.sql("SELECT verified_at IS NOT NULL FROM review_media_assets WHERE id = ?")
                .param(assetId).query(Boolean.class).single()).isTrue();
        assertThat(result.media()).hasSize(1);
    }

    /**
     * Unknown and foreign asset ids answer the same code on purpose, so the pair cannot
     * be used to probe whether an asset exists.
     */
    @Test
    void unknownAndForeignAssetsAreIndistinguishable() {
        UUID eligibilityId = grantEntitlement("order-2", "p-1", "sku-1", buyer);
        UUID foreign = issueAndUpload(UUID.randomUUID());

        assertThat(codeOfCreateFailure(eligibilityId, UUID.randomUUID()))
                .isEqualTo(ErrorCode.INVALID_MEDIA_REFERENCE);
        assertThat(codeOfCreateFailure(eligibilityId, foreign))
                .isEqualTo(ErrorCode.INVALID_MEDIA_REFERENCE);
    }

    /** An owned asset whose object was never uploaded is recoverable by re-uploading. */
    @Test
    void anOwnedAssetWithNoObjectIsNotVerified() {
        UUID eligibilityId = grantEntitlement("order-3", "p-1", "sku-1", buyer);
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));

        assertThat(codeOfCreateFailure(eligibilityId, issued.assetId()))
                .isEqualTo(ErrorCode.MEDIA_NOT_VERIFIED);
    }

    @Test
    void anAlreadyAttachedAssetCannotBeReused() {
        UUID firstEligibility = grantEntitlement("order-4", "p-1", "sku-1", buyer);
        UUID secondEligibility = grantEntitlement("order-5", "p-2", "sku-2", buyer);
        UUID assetId = issueAndUpload(buyer);
        reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(firstEligibility, 5, null, List.of(assetId)));

        assertThat(codeOfCreateFailure(secondEligibility, assetId))
                .isEqualTo(ErrorCode.MEDIA_NOT_VERIFIED);
    }

    /** Replacing media detaches the old asset with its own reason. */
    @Test
    void replacingMediaQueuesTheOldAssetAsReplaced() {
        UUID eligibilityId = grantEntitlement("order-6", "p-1", "sku-1", buyer);
        UUID original = issueAndUpload(buyer);
        UUID replacement = issueAndUpload(buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, List.of(original)))
                .review().id();

        reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.absent(), Patch.absent(), Patch.of(List.of(replacement))));

        assertThat(assetState(original)).isEqualTo("DELETE_PENDING");
        assertThat(deletionReason(original)).isEqualTo("REPLACED");
        assertThat(assetState(replacement)).isEqualTo("ATTACHED");
    }

    /** An empty media array removes every attachment. */
    @Test
    void anEmptyMediaArrayDetachesEverything() {
        UUID eligibilityId = grantEntitlement("order-7", "p-1", "sku-1", buyer);
        UUID assetId = issueAndUpload(buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Có ảnh", List.of(assetId)))
                .review().id();

        var edited = reviewCommands.edit(buyer, reviewId, new PatchReviewRequest(
                Patch.absent(), Patch.absent(), Patch.of(List.of())));

        assertThat(edited.review().hasMedia()).isFalse();
        assertThat(assetState(assetId)).isEqualTo("DELETE_PENDING");
        assertThat(deletionReason(assetId)).isEqualTo("REPLACED");
    }

    /** A legal takedown detaches with its own reason. */
    @Test
    void aTakedownQueuesTheAssetAsTakedown() {
        UUID eligibilityId = grantEntitlement("order-8", "p-1", "sku-1", buyer);
        UUID assetId = issueAndUpload(buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, List.of(assetId)))
                .review().id();

        takedowns.takeDown(admin, reviewId,
                new CommandDtos.CreateTakedownRequest("CASE-9", "vi phạm"));

        assertThat(assetState(assetId)).isEqualTo("DELETE_PENDING");
        assertThat(deletionReason(assetId)).isEqualTo("TAKEDOWN");
    }

    /**
     * An unattached asset that passes its deadline enters the same cleanup pipeline, and
     * an asset that was never uploaded still reaches DELETED because storage answering
     * "not found" is a successful confirmation.
     */
    @Test
    void anExpiredUnattachedAssetIsCleanedUpEvenIfItWasNeverUploaded() {
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));
        jdbc.sql("UPDATE review_media_assets SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(issued.assetId()).update();

        mediaAssets.sweepExpiredUnattached(Instant.now(), 100);
        assertThat(assetState(issued.assetId())).isEqualTo("DELETE_PENDING");
        assertThat(deletionReason(issued.assetId())).isEqualTo("UNATTACHED_EXPIRED");

        int deleted = mediaCleanup.processDeletePending(100);

        assertThat(deleted).isEqualTo(1);
        assertThat(assetState(issued.assetId())).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM review_media_assets WHERE id = ?")
                .param(issued.assetId()).query(Boolean.class).single()).isTrue();
    }

    /** An attached asset is never swept: attaching removed it from the sweep. */
    /**
     * The daily pass works through the whole backlog, not just its first batch: with
     * "process every DELETE_PENDING asset" (WF-REVIEW-011) a queue longer than the batch
     * size would otherwise only ever drain from the front.
     */
    @Test
    void theDailyCleanupDrainsMoreAssetsThanOneBatchHolds() {
        for (int i = 0; i < 5; i++) {
            mediaAssetService.issueUploadInstruction(buyer,
                    new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));
        }
        jdbc.sql("""
                UPDATE review_media_assets
                   SET upload_state = 'DELETE_PENDING', deletion_reason = 'UNATTACHED_EXPIRED',
                       delete_requested_at = now()
                """).update();

        int deleted = mediaCleanup.processDeletePending(2);

        assertThat(deleted).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets WHERE upload_state = 'DELETED'")
                .query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets WHERE upload_state = 'DELETE_PENDING'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void anAttachedAssetIsNeverSwept() {
        UUID eligibilityId = grantEntitlement("order-9", "p-1", "sku-1", buyer);
        UUID assetId = issueAndUpload(buyer);
        reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, List.of(assetId)));
        jdbc.sql("UPDATE review_media_assets SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(assetId).update();

        mediaAssets.sweepExpiredUnattached(Instant.now(), 100);

        assertThat(assetState(assetId)).isEqualTo("ATTACHED");
    }

    /** An asset already queued for cleanup is rejected as unusable. */
    @Test
    void anAssetQueuedForCleanupCannotBeAttached() {
        UUID eligibilityId = grantEntitlement("order-10", "p-1", "sku-1", buyer);
        UUID assetId = issueAndUpload(buyer);
        jdbc.sql("UPDATE review_media_assets SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(assetId).update();
        mediaAssets.sweepExpiredUnattached(Instant.now(), 100);

        assertThat(codeOfCreateFailure(eligibilityId, assetId)).isEqualTo(ErrorCode.MEDIA_NOT_VERIFIED);
    }

    @Test
    void theUnattachedAssetQuotaIsEnforced() {
        for (int i = 0; i < properties.media().maxUnattachedAssetsPerUser(); i++) {
            mediaAssetService.issueUploadInstruction(buyer,
                    new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null));
        }

        assertThatThrownBy(() -> mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg", 1024L, null)))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).code())
                .isEqualTo(ErrorCode.MEDIA_QUOTA_EXCEEDED);
    }

    @Test
    void anOversizedOrWrongTypeUploadIsRefusedBeforeAnyKeyIsIssued() {
        assertThatThrownBy(() -> mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg",
                        properties.media().maxImageBytes() + 1, null)))
                .isInstanceOf(ReviewException.class);

        assertThatThrownBy(() -> mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "application/pdf", 1024L, null)))
                .isInstanceOf(ReviewException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets").query(Long.class).single())
                .isZero();
    }

    private ErrorCode codeOfCreateFailure(UUID eligibilityId, UUID assetId) {
        try {
            reviewCommands.create(buyer,
                    new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, List.of(assetId)));
            throw new AssertionError("the create was expected to fail");
        } catch (ReviewException e) {
            return e.code();
        }
    }

    private String assetState(UUID assetId) {
        return jdbc.sql("SELECT upload_state FROM review_media_assets WHERE id = ?")
                .param(assetId).query(String.class).single();
    }

    private String deletionReason(UUID assetId) {
        return jdbc.sql("SELECT deletion_reason FROM review_media_assets WHERE id = ?")
                .param(assetId).query(String.class).single();
    }
}
