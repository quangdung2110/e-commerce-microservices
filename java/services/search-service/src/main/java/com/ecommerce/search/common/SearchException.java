package com.ecommerce.search.common;

import java.util.List;

/**
 * Every error surfaced over HTTP carries an {@link ErrorCode}; the exception handler maps
 * it to a {@code application/problem+json} body with {@code code}, {@code trace_id},
 * {@code retryable} and {@code errors[]}.
 */
public class SearchException extends RuntimeException {

    /** One field-level violation inside {@code errors[]} of the Problem body. */
    public record FieldError(String field, String message) {
    }

    private final transient ErrorCode code;
    private final transient List<FieldError> errors;

    public SearchException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public SearchException(ErrorCode code, String detail, Throwable cause) {
        this(code, detail, List.of(), cause);
    }

    public SearchException(ErrorCode code, String detail, List<FieldError> errors, Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public static SearchException invalidParameter(String field, String message) {
        return new SearchException(
                ErrorCode.INVALID_QUERY_PARAMETER,
                "Request parameter '" + field + "' is not acceptable: " + message,
                List.of(new FieldError(field, message)),
                null);
    }

    public static SearchException invalidCursor(String message) {
        return new SearchException(
                ErrorCode.INVALID_CURSOR,
                message,
                List.of(new FieldError("cursor", message)),
                null);
    }

    public static SearchException categoryNotFound(String categoryId) {
        return new SearchException(ErrorCode.CATEGORY_NOT_FOUND, "Category " + categoryId + " is not in the projection");
    }

    public static SearchException reindexRunNotFound(String runId) {
        return new SearchException(ErrorCode.REINDEX_RUN_NOT_FOUND, "Reindex run " + runId + " does not exist");
    }
}
