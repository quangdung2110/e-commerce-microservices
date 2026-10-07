package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One entry of the {@code search_suggestions} index.
 *
 * <p>A suggestion is a query-completion term, not a link to an entity: the public response
 * exposes only {@code text} and {@code type}, and the client uses {@code text} as the
 * {@code q} of the product search. {@code categoryId} / {@code productId} are kept for
 * operational traceability of the build and never leave the service.
 *
 * <p>{@code suggestionId} is the SHA-256 of {@code (type, scope, language,
 * text_normalized)} joined with NUL, so a rebuild is deterministic
 * ({@code docs/search/data.md §1.2}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SuggestionDocument(
        String suggestionId,
        String text,
        String textNormalized,
        String type,
        String source,
        String scope,
        String language,

        String categoryId,
        String productId,

        Long weight,
        Double productRatingAvg,
        Long productRatingCount,
        Long productCount,

        Long buildVersion,
        Instant updatedAt) {
}
