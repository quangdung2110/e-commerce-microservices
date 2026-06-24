package com.ecommerce.search.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_QUERY_PARAMETER("INVALID_QUERY_PARAMETER", 400, "Invalid query parameter"),
    INVALID_CURSOR("INVALID_CURSOR", 400, "Invalid cursor"),
    UNAUTHORIZED("UNAUTHORIZED", 401, "Unauthorized"),
    FORBIDDEN("FORBIDDEN", 403, "Forbidden"),
    CATEGORY_NOT_FOUND("CATEGORY_NOT_FOUND", 404, "Category not found"),
    INVALID_LANGUAGE("INVALID_LANGUAGE", 422, "Invalid language"),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "Internal server error"),
    REINDEX_ALREADY_RUNNING("REINDEX_ALREADY_RUNNING", 409, "Reindex already running"),
    ELASTICSEARCH_UNAVAILABLE("ELASTICSEARCH_UNAVAILABLE", 503, "Elasticsearch unavailable"),
    SEARCH_TIMEOUT("SEARCH_TIMEOUT", 504, "Search timeout");

    private final String code;
    private final int httpStatus;
    private final String message;
}
