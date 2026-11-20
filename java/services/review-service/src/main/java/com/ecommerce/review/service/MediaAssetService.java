package com.ecommerce.review.service;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.MediaAsset;
import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.TransactionClock;
import com.ecommerce.review.storage.ObjectKeys;
import com.ecommerce.review.storage.ReviewObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * WF-REVIEW-002: issuing upload instructions and verifying objects before attachment.
 *
 * <p>The client has exactly three steps — ask for an instruction, upload, send the
 * {@code asset_id} with the review. There is no verify or poll endpoint: pushing the
 * storage state machine into the frontend adds round trips and no business value
 * (ADR-011).
 */
@Service
public class MediaAssetService {

    private final MediaAssetRepository mediaAssets;
    private final ReviewObjectStorage storage;
    private final ObjectKeys objectKeys;
    private final PrivacyGuardService privacyGuard;
    private final TransactionClock clock;
    private final ReviewProperties properties;
    private final ReviewMetrics metrics;

    public MediaAssetService(MediaAssetRepository mediaAssets,
                             ReviewObjectStorage storage,
                             ObjectKeys objectKeys,
                             PrivacyGuardService privacyGuard,
                             TransactionClock clock,
                             ReviewProperties properties,
                             ReviewMetrics metrics) {
        this.mediaAssets = mediaAssets;
        this.storage = storage;
        this.objectKeys = objectKeys;
        this.privacyGuard = privacyGuard;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** An asset whose object was read and found acceptable, ready to be attached. */
    public record VerifiedAsset(UUID assetId, String contentType, long byteSize, String checksum) {
    }

    /**
     * Issues one {@code ISSUED} asset and its signed upload instruction.
     *
     * <p>Signing is a local cryptographic operation against configured credentials, so
     * it is not a storage side effect and does not break the rule that nothing external
     * happens before commit. The object key is generated here and never returned.
     */
    @Transactional
    public CommandDtos.IssuedMediaAsset issueUploadInstruction(UUID callerId,
                                                               CommandDtos.IssueMediaUploadRequest request) {
        privacyGuard.lockAndRequireUnguarded(callerId, "media-upload-issue");

        MediaType mediaType = request.mediaType();
        validateDeclaredContentType(mediaType, request.contentType());
        validateDeclaredSize(mediaType, request.byteSize());
        enforceUnattachedQuota(callerId, request.byteSize());

        Instant now = clock.now();
        Instant expiresAt = now.plus(properties.media().uploadTtl());
        String objectKey = objectKeys.generate(mediaType, now);

        MediaAsset asset = mediaAssets.insertIssued(callerId, objectKey, mediaType,
                request.contentType(), request.byteSize(), request.checksum(), expiresAt);

        ReviewObjectStorage.UploadInstruction instruction =
                storage.presignUpload(objectKey, request.contentType(), request.byteSize(),
                        properties.media().uploadTtl());

        return new CommandDtos.IssuedMediaAsset(asset.id(), expiresAt,
                new CommandDtos.UploadInstruction(instruction.method(), instruction.url(),
                        instruction.headers()));
    }

    /**
     * Reads and checks the immutable metadata of every submitted asset, before the
     * command transaction opens.
     *
     * <p>The two rejections mean different things to a client. An asset id that does not
     * resolve to a usable asset of the caller is {@code INVALID_MEDIA_REFERENCE} and can
     * never be recovered; unknown and foreign share that code on purpose, so the pair
     * cannot be used to probe whether an asset exists. A caller-owned asset whose object
     * is missing, mismatched, expired or already attached is {@code MEDIA_NOT_VERIFIED},
     * which a fresh upload does recover.
     */
    public List<VerifiedAsset> verifyForAttachment(UUID callerId, List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        if (assetIds.size() > properties.maxMediaPerReview()) {
            throw ReviewException.invalid("media_asset_ids",
                    "a review carries at most " + properties.maxMediaPerReview() + " attachments");
        }
        if (new java.util.HashSet<>(assetIds).size() != assetIds.size()) {
            // The contract declares the list uniqueItems. Left to the attach step, a repeated
            // id would surface as MEDIA_NOT_VERIFIED (the first copy had already attached it),
            // which tells the client to re-upload something that was never wrong.
            throw ReviewException.invalid("media_asset_ids", "asset identifiers must be unique");
        }
        Instant now = Instant.now();
        List<VerifiedAsset> verified = new ArrayList<>(assetIds.size());
        for (UUID assetId : assetIds) {
            MediaAsset asset = mediaAssets.findById(assetId)
                    .filter(candidate -> callerId.equals(candidate.ownerUserId()))
                    .orElseThrow(() -> new ReviewException(ErrorCode.INVALID_MEDIA_REFERENCE,
                            "the media asset reference does not resolve to a usable asset"));
            verified.add(verifyObject(asset, now));
        }
        return verified;
    }

    private VerifiedAsset verifyObject(MediaAsset asset, Instant now) {
        String mediaType = asset.mediaType().name();
        if (!asset.attachable(now)) {
            metrics.mediaVerification("not_attachable", mediaType);
            throw notVerified();
        }
        ReviewObjectStorage.StoredObject object = storage.head(asset.objectKey())
                .orElseThrow(() -> {
                    metrics.mediaVerification("object_missing", mediaType);
                    return notVerified();
                });

        // A storage server that reports no content type has not contradicted anything:
        // the presigned upload pinned the declared one, so that is what was written.
        String actualContentType = object.contentType() == null ? asset.contentType() : object.contentType();
        if (!actualContentType.equalsIgnoreCase(asset.contentType())) {
            metrics.mediaVerification("content_type_mismatch", mediaType);
            throw notVerified();
        }
        if (asset.byteSize() != null && object.byteSize() != asset.byteSize()) {
            metrics.mediaVerification("size_mismatch", mediaType);
            throw notVerified();
        }
        if (object.byteSize() > maxBytes(asset.mediaType())) {
            metrics.mediaVerification("size_over_limit", mediaType);
            throw notVerified();
        }
        if (asset.checksum() != null && !asset.checksum().equalsIgnoreCase(object.checksum())) {
            metrics.mediaVerification("checksum_mismatch", mediaType);
            throw notVerified();
        }

        metrics.mediaVerification("verified", mediaType);
        return new VerifiedAsset(asset.id(), actualContentType, object.byteSize(), object.checksum());
    }

    private static ReviewException notVerified() {
        return new ReviewException(ErrorCode.MEDIA_NOT_VERIFIED,
                "the uploaded object is not usable; upload the media again");
    }

    /**
     * Re-checks each asset under its row lock and attaches it, in submitted order.
     *
     * <p>This lock is the same one the expiry sweep takes, which is what makes the race
     * have exactly one winner: an asset already queued for cleanup is rejected here as
     * unusable, and an asset attached here is no longer visible to the sweep.
     */
    public List<UUID> lockAndAttach(UUID callerId, List<VerifiedAsset> assets, Instant now) {
        List<UUID> attached = new ArrayList<>(assets.size());
        for (VerifiedAsset verified : assets) {
            MediaAsset locked = mediaAssets.findByIdForUpdate(verified.assetId())
                    .filter(asset -> callerId.equals(asset.ownerUserId()))
                    .orElseThrow(() -> new ReviewException(ErrorCode.INVALID_MEDIA_REFERENCE,
                            "the media asset reference does not resolve to a usable asset"));
            if (!locked.attachable(now)) {
                throw notVerified();
            }
            mediaAssets.markVerifiedAndAttached(verified.assetId(), verified.contentType(),
                    verified.byteSize(), verified.checksum(), now);
            attached.add(verified.assetId());
        }
        return attached;
    }

    private void enforceUnattachedQuota(UUID callerId, long requestedBytes) {
        long assets = mediaAssets.countUnattachedByOwner(callerId);
        long bytes = mediaAssets.sumUnattachedBytesByOwner(callerId);
        if (assets >= properties.media().maxUnattachedAssetsPerUser()
                || bytes + requestedBytes > properties.media().maxUnattachedBytesPerUser()) {
            // Without both quotas, direct-to-storage upload is unmetered storage for
            // anyone with an account (docs/review/data.md 4).
            metrics.mediaQuotaRejected();
            throw new ReviewException(ErrorCode.MEDIA_QUOTA_EXCEEDED,
                    "the unattached media quota is exhausted; attach or discard pending uploads first");
        }
    }

    private void validateDeclaredContentType(MediaType mediaType, String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        var allowed = mediaType == MediaType.IMAGE
                ? properties.media().imageContentTypes()
                : properties.media().videoContentTypes();
        if (!allowed.contains(normalized)) {
            throw ReviewException.invalid("content_type",
                    "content_type is not an accepted " + mediaType.name().toLowerCase(Locale.ROOT) + " type");
        }
    }

    private void validateDeclaredSize(MediaType mediaType, long byteSize) {
        if (byteSize > maxBytes(mediaType)) {
            throw ReviewException.invalid("byte_size",
                    "byte_size exceeds the configured limit for " + mediaType.name().toLowerCase(Locale.ROOT));
        }
    }

    private long maxBytes(MediaType mediaType) {
        return mediaType == MediaType.IMAGE
                ? properties.media().maxImageBytes()
                : properties.media().maxVideoBytes();
    }
}
