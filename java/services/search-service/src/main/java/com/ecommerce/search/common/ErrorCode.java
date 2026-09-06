package com.ecommerce.search.common;

import org.springframework.http.HttpStatus;

/**
 * Every error Search can return, exactly as listed in {@code docs/search/domain.md §8}.
 *
 * <p>The first six are domain errors. The last three are infrastructure failures and must
 * never be used to infer a business conclusion — an empty result set is a valid result,
 * not an error.
 */
public enum ErrorCode {

    INVALID_QUERY_PARAMETER(HttpStatus.BAD_REQUEST, false, "Invalid query parameter"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, false, "Invalid cursor"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false, "Missing trusted gateway identity"),
    FORBIDDEN(HttpStatus.FORBIDDEN, false, "Forbidden"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Category not found"),
    REINDEX_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Reindex run not found"),
    REINDEX_ALREADY_RUNNING(HttpStatus.CONFLICT, true, "A reindex run is already in progress"),

    ELASTICSEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Elasticsearch unavailable"),
    SEARCH_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true, "Search timed out"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "Internal error");

    private final HttpStatus status;
    private final boolean retryable;
    private final String title;

    ErrorCode(HttpStatus status, boolean retryable, String title) {
        this.status = status;
        this.retryable = retryable;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String title() {
        return title;
    }

    /** Stable {@code type} URI used in the Problem Details body. */
    public String typeUri() {
        return "https://contracts.ecommerce.local/problems/search/" + name().toLowerCase().replace('_', '-');
    }
}
