package com.ecommerce.review.controller;

import com.ecommerce.review.config.CallerResolver;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.service.ReviewQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * The buyer surface: pending entitlements, the two recovery reads, create, the single
 * edit and reporting.
 *
 * <p>{@code X-Request-Id} is correlation only. A client whose create or edit timed out
 * uses the recovery read rather than replaying the command, which is why neither command
 * accepts an idempotency key.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Validated
public class BuyerReviewController {

    private final ReviewCommandService reviewCommands;
    private final ReviewQueryService reviewQueries;
    private final ModerationService moderation;
    private final CallerResolver callers;

    public BuyerReviewController(ReviewCommandService reviewCommands,
                                 ReviewQueryService reviewQueries,
                                 ModerationService moderation,
                                 CallerResolver callers) {
        this.reviewCommands = reviewCommands;
        this.reviewQueries = reviewQueries;
        this.moderation = moderation;
        this.callers = callers;
    }

    @GetMapping("/me/pending")
    public PublicDtos.PendingEligibilityPage listPending(
            HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        return reviewQueries.listPending(callers.requireUserId(request), cursor, limit);
    }

    /** The buyer's own reviews, newest first, including those no longer public. */
    @GetMapping("/me")
    public PublicDtos.OwnerReviewPage listMyReviews(
            HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        return reviewQueries.listMyReviews(callers.requireUserId(request), cursor, limit);
    }

    /** Resolves a create whose response was lost: a claimed entitlement names its review. */
    @GetMapping("/me/eligibilities/{eligibility_id}")
    public PublicDtos.MyEligibilityReviewStatus getMyEligibilityStatus(
            HttpServletRequest request,
            @PathVariable("eligibility_id") UUID eligibilityId) {
        return reviewQueries.getMyEligibilityStatus(callers.requireUserId(request), eligibilityId);
    }

    /** Resolves an edit whose response was lost. */
    @GetMapping("/me/{review_id}")
    public PublicDtos.OwnerReview getMyReview(HttpServletRequest request,
                                              @PathVariable("review_id") UUID reviewId) {
        return reviewQueries.getMyReview(callers.requireUserId(request), reviewId);
    }

    @PostMapping
    public ResponseEntity<PublicDtos.OwnerReview> createReview(
            HttpServletRequest request,
            @Valid @RequestBody CommandDtos.CreateReviewRequest body) {
        UUID callerId = callers.requireUserId(request);
        PublicDtos.OwnerReview created = reviewQueries.toOwnerReview(reviewCommands.create(callerId, body));
        return ResponseEntity.created(URI.create("/api/v1/reviews/" + created.reviewId())).body(created);
    }

    /**
     * The single permitted edit.
     *
     * <p>The body is taken as a raw JSON tree because the contract needs three states per
     * field: absent preserves, {@code content: null} clears, and an empty
     * {@code media_asset_ids} removes every attachment.
     */
    @PatchMapping("/{review_id}")
    public PublicDtos.OwnerReview patchReview(HttpServletRequest request,
                                              @PathVariable("review_id") UUID reviewId,
                                              @RequestBody JsonNode body) {
        UUID callerId = callers.requireUserId(request);
        return reviewQueries.toOwnerReview(
                reviewCommands.edit(callerId, reviewId, PatchReviewRequest.from(body)));
    }

    /**
     * Reporting is deliberately answered with an empty {@code 202}: a body would leak the
     * active report count or the fact that this report just hid the review (ADR-008).
     */
    @PostMapping("/{review_id}/reports")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public void reportReview(HttpServletRequest request,
                             @PathVariable("review_id") UUID reviewId,
                             @Valid @RequestBody CommandDtos.CreateReviewReportRequest body) {
        moderation.report(callers.requireUserId(request), reviewId, body);
    }
}
