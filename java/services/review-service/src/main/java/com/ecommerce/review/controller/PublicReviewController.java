package com.ecommerce.review.controller;

import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.service.ReviewQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymous read surface: approved reviews of a product and its rating aggregate.
 */
@RestController
@RequestMapping("/api/v1/reviews/products/{product_id}")
@Validated
public class PublicReviewController {

    private final ReviewQueryService reviewQueries;

    public PublicReviewController(ReviewQueryService reviewQueries) {
        this.reviewQueries = reviewQueries;
    }

    @GetMapping
    public PublicDtos.PublicReviewPage listProductReviews(
            @PathVariable("product_id") @Size(min = 1, max = 64) String productId,
            @RequestParam(name = "sku_id", required = false) @Size(min = 1, max = 64) String skuId,
            @RequestParam(name = "rating", required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(name = "has_media", required = false) Boolean hasMedia,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        if (Boolean.FALSE.equals(hasMedia)) {
            // has_media=false would mean "no filter", which the contract expresses by
            // omitting the parameter. Accepting it would leave two spellings of one
            // query and no index decision attached to either.
            throw ReviewException.invalid("has_media", "has_media only accepts true; omit it for no filter");
        }
        return reviewQueries.listProductReviews(productId, skuId, rating,
                Boolean.TRUE.equals(hasMedia), cursor, limit);
    }

    /** Zero counters when no aggregate row exists; that is a valid answer, not a 404. */
    @GetMapping("/aggregate")
    public PublicDtos.ProductReviewAggregate getAggregate(
            @PathVariable("product_id") @Size(min = 1, max = 64) String productId) {
        return reviewQueries.getAggregate(productId);
    }
}
