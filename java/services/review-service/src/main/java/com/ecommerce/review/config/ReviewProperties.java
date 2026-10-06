package com.ecommerce.review.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Every tunable named in {@code docs/review/operations.md 3}.
 *
 * <p>The two HMAC secrets have no default on purpose. Rotating the alias secret starts
 * a new pseudonym namespace and rotating the privacy-guard secret orphans every
 * committed deletion guard, so a missing value must be a visible startup failure rather
 * than a silently generated key.
 */
@ConfigurationProperties(prefix = "review")
public record ReviewProperties(
        @DefaultValue("30") int windowDays,
        @DefaultValue("5000") int maxContentLength,
        @DefaultValue("9") int maxMediaPerReview,
        @DefaultValue("10") int reportThreshold,
        @DefaultValue("30m") Duration exportSessionTtl,
        @DefaultValue("30") int eventReceiptRetentionDays,
        @DefaultValue("30") int outboxRetentionDays,
        Secrets secrets,
        Media media,
        Storage storage,
        Gateway gateway,
        Internal internal,
        Topics topics,
        ConsumerGroups consumerGroups,
        Scheduler scheduler) {

    public record Secrets(
            @DefaultValue("") String authorAliasHmac,
            @DefaultValue("") String privacyGuardHmac) {
    }

    public record Media(
            @DefaultValue("15m") Duration uploadTtl,
            @DefaultValue("18") int maxUnattachedAssetsPerUser,
            @DefaultValue("536870912") long maxUnattachedBytesPerUser,
            @DefaultValue("10485760") long maxImageBytes,
            @DefaultValue("104857600") long maxVideoBytes,
            @DefaultValue({"image/jpeg", "image/png", "image/webp"}) Set<String> imageContentTypes,
            @DefaultValue({"video/mp4", "video/webm"}) Set<String> videoContentTypes,
            @DefaultValue("5m") Duration publicUrlTtl,
            @DefaultValue("2m") Duration adminUrlTtl) {
    }

    public record Storage(
            @DefaultValue("http://localhost:9000") String endpoint,
            @DefaultValue("") String publicEndpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("review-media") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("true") boolean pathStyleAccess) {
        public String signingEndpoint() {
            return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        }
    }

    public record Gateway(
            @DefaultValue("X-User-Id") String userIdHeader,
            @DefaultValue("X-User-Roles") String rolesHeader,
            @DefaultValue("X-Request-Id") String requestIdHeader,
            @DefaultValue("ADMIN") String adminRole) {
    }

    /**
     * Bearer tokens accepted on {@code /internal/v1/**}, split by route group so the
     * Product Detail BFF cannot page the rating export and Search cannot read the PDP
     * summary. An empty list rejects every caller on that group.
     */
    public record Internal(
            @DefaultValue("") List<String> pdpTokens,
            @DefaultValue("") List<String> exportTokens) {
    }

    public record Topics(
            @DefaultValue("t.review.events") String review,
            @DefaultValue("t.order.events") String order,
            @DefaultValue("t.auth.events") String auth) {
    }

    public record ConsumerGroups(
            @DefaultValue("review-order-sync") String order,
            @DefaultValue("review-privacy-sync") String privacy,
            @DefaultValue("review-rating-aggregator") String rating) {
    }

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 */5 * * * *") String lifecycleCron,
            @DefaultValue("0 15 2 * * *") String dailyCron,
            @DefaultValue("500") int sweepBatchSize) {
    }

    @PostConstruct
    void validate() {
        if (maxMediaPerReview < 1 || maxMediaPerReview > 9) {
            throw new IllegalStateException(
                    "review.max-media-per-review must stay within the 9 attachments the public contract allows");
        }
        if (maxContentLength > 5000) {
            throw new IllegalStateException(
                    "review.max-content-length cannot exceed the ck_review_content_valid cap without a migration");
        }
        if (!media.adminUrlTtl().minus(media.publicUrlTtl()).isNegative()) {
            throw new IllegalStateException(
                    "review.media.admin-url-ttl must be shorter than review.media.public-url-ttl: "
                            + "the admin surface is the only place a non-APPROVED review gets a signed URL");
        }
        if (eventReceiptRetentionDays < 14) {
            throw new IllegalStateException(
                    "review.event-receipt-retention-days must exceed the 14d Kafka retention plus a replay margin");
        }
    }

    /** True once both required HMAC secrets are present; readiness depends on it. */
    public boolean secretsConfigured() {
        return secrets != null
                && secrets.authorAliasHmac() != null && !secrets.authorAliasHmac().isBlank()
                && secrets.privacyGuardHmac() != null && !secrets.privacyGuardHmac().isBlank();
    }
}
