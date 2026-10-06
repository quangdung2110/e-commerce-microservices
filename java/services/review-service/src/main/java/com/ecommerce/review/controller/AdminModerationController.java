package com.ecommerce.review.controller;

import com.ecommerce.review.config.CallerResolver;
import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.dto.AdminDtos;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.TakedownService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The single admin moderation surface.
 *
 * <p>Its unit is the review, not the report: {@code report-dismissals} acts on a review,
 * so three reports on one review are one case and one row. This is also the only place
 * that issues a signed media URL for a review that is not {@code APPROVED} — with a TTL
 * shorter than the public one — because a report about an image cannot be judged without
 * seeing it.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
@Validated
public class AdminModerationController {

    private final ModerationService moderation;
    private final AdminReplyService adminReplies;
    private final TakedownService takedowns;
    private final CallerResolver callers;

    public AdminModerationController(ModerationService moderation,
                                     AdminReplyService adminReplies,
                                     TakedownService takedowns,
                                     CallerResolver callers) {
        this.moderation = moderation;
        this.adminReplies = adminReplies;
        this.takedowns = takedowns;
        this.callers = callers;
    }

    /**
     * {@code status} is required: a client that forgot the filter would otherwise page
     * through every review in the system.
     */
    @GetMapping
    public AdminDtos.AdminModerationCasePage listQueue(
            HttpServletRequest request,
            @RequestParam("status") ModerationQueueStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit) {
        callers.requireAdminId(request);
        return moderation.listQueue(status, cursor, limit);
    }

    @PostMapping("/{review_id}/report-dismissals")
    public AdminDtos.AdminModerationResult dismissReports(HttpServletRequest request,
                                                          @PathVariable("review_id") UUID reviewId) {
        return moderation.dismissReports(callers.requireAdminId(request), reviewId);
    }

    /** Resource-level upsert; only the first creation can request a notification. */
    @PutMapping("/{review_id}/reply")
    public PublicDtos.OfficialReply putReply(HttpServletRequest request,
                                             @PathVariable("review_id") UUID reviewId,
                                             @Valid @RequestBody CommandDtos.PutAdminReplyRequest body) {
        return adminReplies.putReply(callers.requireAdminId(request), reviewId, body);
    }

    /**
     * The acting administrator comes from trusted Gateway identity and is persisted as
     * {@code taken_down_by_admin_id}; the body carries no actor field (ADR-010).
     */
    @PostMapping("/{review_id}/takedown")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandDtos.TakedownReceipt takeDown(HttpServletRequest request,
                                                @PathVariable("review_id") UUID reviewId,
                                                @Valid @RequestBody CommandDtos.CreateTakedownRequest body) {
        return takedowns.takeDown(callers.requireAdminId(request), reviewId, body);
    }
}
