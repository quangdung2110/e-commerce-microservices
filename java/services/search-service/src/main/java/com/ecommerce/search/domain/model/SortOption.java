package com.ecommerce.search.domain.model;

import com.ecommerce.search.common.SearchException;

import java.util.Locale;

/**
 * The five sorts the storefront contract exposes.
 *
 * <p>Every one of them ends with the {@code sku_id asc} tie-break added by the query
 * builder ({@code SEARCH-BR-006}); without it {@code search_after} would repeat or skip
 * documents.
 *
 * <p>{@code NEWEST} sorts on {@code updated_at}: the target mapping carries no
 * {@code created_at}, and {@code catalog_upsert.painless} does not allow writing one, so
 * {@code updated_at} is the only catalog date available.
 */
public enum SortOption {

    RELEVANCE("relevance"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc"),
    NEWEST("newest"),
    RATING("rating");

    private final String wireValue;

    SortOption(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SortOption parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RELEVANCE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SortOption option : values()) {
            if (option.wireValue.equals(normalized)) {
                return option;
            }
        }
        throw SearchException.invalidParameter("sort",
                "must be one of relevance, price_asc, price_desc, newest, rating");
    }
}
