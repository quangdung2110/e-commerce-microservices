package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;

/**
 * Elasticsearch document mapping 1:1 with {@code search_suggestions_v1} index.
 *
 * <p>This index is rebuilt atomically on a daily cadence by {@code SuggestionRebuilder}.
 * Because there are no partial updates, no {@code version} field is needed —
 * ordering is guaranteed by the atomic alias-swap strategy.
 *
 * <p>{@code productCount} is only meaningful for {@code CATEGORY} type suggestions.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuggestionDocument(
    @JsonProperty("suggestion_id") String suggestionId,
    String text,
    @JsonProperty("text_normalized") String textNormalized,
    String type,
    String source,
    String scope,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("product_id") String productId,
    Long weight,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("product_rating_count") Integer productRatingCount,
    @JsonProperty("product_count") Long productCount,
    @JsonProperty("build_version") Long buildVersion,
    @JsonProperty("updated_at") Instant updatedAt
) {}
