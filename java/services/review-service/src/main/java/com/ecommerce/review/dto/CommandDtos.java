package com.ecommerce.review.dto;

import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.domain.ReportReason;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The buyer and admin request bodies, plus the two command receipts.
 *
 * <p>{@code X-Request-Id} is a correlation identifier and not an idempotency key, so
 * none of these carries one. A client whose create or edit timed out uses the recovery
 * read rather than replaying the command.
 */
public final class CommandDtos {

    private CommandDtos() {
    }

    /**
     * @param mediaAssetIds caller-owned, already-uploaded assets; the contract exchanges
     *                      asset identifiers, never object keys or arbitrary URLs
     */
    public record CreateReviewRequest(
            @NotNull UUID eligibilityId,
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 5000) String content,
            @Size(max = 9) List<UUID> mediaAssetIds) {
    }

    public record CreateReviewReportRequest(
            @NotNull ReportReason reason,
            @Size(max = 2000) String detail) {
    }

    public record IssueMediaUploadRequest(
            @NotNull MediaType mediaType,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Min(1) Long byteSize,
            @Size(min = 1, max = 128) String checksum) {
    }

    /** Provider-neutral: the object key is not in it, and the method is always PUT. */
    public record UploadInstruction(String method, String url, Map<String, String> headers) {
    }

    public record IssuedMediaAsset(UUID assetId, Instant expiresAt, UploadInstruction upload) {
    }

    public record PutAdminReplyRequest(@NotBlank @Size(max = 5000) String content) {
    }

    /**
     * No actor field. The single acting administrator is read from trusted Gateway
     * identity, because a body-supplied approver identifier would prove nothing
     * (ADR-010).
     */
    public record CreateTakedownRequest(
            @NotBlank @Size(max = 128) String legalCaseId,
            @NotBlank @Size(max = 5000) String reason) {
    }

    /** The takedown receipt; it carries the case reference but never the internal reason. */
    public record TakedownReceipt(UUID takedownId, UUID reviewId, String legalCaseId, Instant createdAt) {
    }
}
