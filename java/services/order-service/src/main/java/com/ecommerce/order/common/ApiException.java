package com.ecommerce.order.common;

import java.util.List;
import java.util.Map;

/** A request-scoped failure rendered as RFC 9457 problem+json by the web layer. */
public class ApiException extends RuntimeException {

    public record FieldError(String field, String message) {
    }

    private final ErrorCode code;
    private final List<FieldError> errors;
    private final Integer retryAfterSeconds;
    private final Map<String, Object> extensions;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null, Map.of());
    }

    public ApiException(ErrorCode code, String detail, List<FieldError> errors, Integer retryAfterSeconds,
                        Map<String, Object> extensions) {
        super(detail);
        this.code = code;
        this.errors = errors == null ? List.of() : errors;
        this.retryAfterSeconds = retryAfterSeconds;
        this.extensions = extensions == null ? Map.of() : extensions;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Order resource not found");
    }

    public static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.INVALID_REQUEST, detail);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }
}
