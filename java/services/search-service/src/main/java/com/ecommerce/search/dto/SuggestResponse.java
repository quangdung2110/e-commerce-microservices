package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response DTO for {@code GET /api/v1/search/suggest}.
 *
 * <p>Returns a ranked list of autocomplete suggestions from the
 * {@code search_suggestions} index.
 */
public record SuggestResponse(List<SuggestItem> suggestions) {

    public record SuggestItem(
        String text,
        String type,
        Long weight
    ) {}
}
