package com.ecommerce.review.common;

import java.util.List;

/**
 * Every failure surfaced over HTTP carries an {@link ErrorCode}; the exception handler
 * renders it as RFC 9457 {@code application/problem+json} with {@code code},
 * {@code trace_id}, {@code retryable} and {@code errors[]}.
 */
public class ReviewException extends RuntimeException {

    /** One field-level violation inside {@code errors[]} of the Problem body. */
    public record FieldError(String field, String message) {
    }

    private final transient ErrorCode code;
    private final transient List<FieldError> errors;

    public ReviewException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public ReviewException(ErrorCode code, String detail, Throwable cause) {
        this(code, detail, List.of(), cause);
    }

    public ReviewException(ErrorCode code, String detail, List<FieldError> errors, Throwable cause) {
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

    public static ReviewException invalid(String field, String message) {
        return new ReviewException(ErrorCode.INVALID_REQUEST,
                "Request field '" + field + "' is not acceptable: " + message,
                List.of(new FieldError(field, message)), null);
    }

    /**
     * The single response for a caller carrying a committed HMAC deletion guard. It is
     * deliberately the generic authorization failure: adding an {@code ACCOUNT_DELETED}
     * code would turn the API into an oracle for account deletion (WF-REVIEW-010).
     */
    public static ReviewException guarded(String operation) {
        return new ReviewException(ErrorCode.FORBIDDEN, "Operation " + operation + " is not permitted");
    }
}
