package com.ecommerce.review.controller;

import com.ecommerce.review.dto.InternalDtos;
import com.ecommerce.review.service.RatingExportService;
import com.ecommerce.review.service.ReviewQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The internal surface: the PDP summary for Product Detail BFF and the rating export for
 * Search. Neither route is exposed through the API Gateway, and each has its own bearer
 * token list.
 */
@RestController
@RequestMapping("/internal/v1/reviews")
@Validated
public class InternalReviewController {

    private final ReviewQueryService reviewQueries;
    private final RatingExportService ratingExports;

    public InternalReviewController(ReviewQueryService reviewQueries, RatingExportService ratingExports) {
        this.reviewQueries = reviewQueries;
        this.ratingExports = ratingExports;
    }

    /**
     * A product with no review answers a zero aggregate and an empty preview. A store
     * failure answers 503, so the BFF can degrade the review section instead of
     * rendering a fabricated empty one.
     */
    @GetMapping("/products/{product_id}/pdp-summary")
    public InternalDtos.ProductReviewPdpSummary pdpSummary(
            @PathVariable("product_id") @Size(min = 1, max = 64) String productId,
            @RequestParam(name = "preview_limit", defaultValue = "3") @Min(1) @Max(5) int previewLimit) {
        return reviewQueries.pdpSummary(productId, previewLimit);
    }

    /** Creating a session writes durable state, so it is a POST and not a GET (ADR-006). */
    @PostMapping("/rating-aggregate-exports")
    @ResponseStatus(HttpStatus.CREATED)
    public InternalDtos.RatingAggregateExportSession createExportSession() {
        return ratingExports.createSession("search-service");
    }

    @GetMapping("/rating-aggregate-exports/{session_id}")
    public InternalDtos.RatingAggregateExportPage exportPage(
            @PathVariable("session_id") UUID sessionId,
            @RequestParam(name = "after_product_id", required = false) @Size(max = 64) String afterProductId,
            @RequestParam(name = "limit", defaultValue = "500") @Min(1) @Max(1000) int limit) {
        return ratingExports.page(sessionId, afterProductId, limit);
    }
}
