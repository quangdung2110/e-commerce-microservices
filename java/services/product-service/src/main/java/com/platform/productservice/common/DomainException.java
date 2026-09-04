package com.platform.productservice.common;

import java.io.Serial;
import java.util.List;

/**
 * Lỗi nghiệp vụ được map sang RFC 9457 problem+json bởi
 * {@code com.platform.productservice.web.GlobalExceptionHandler}.
 */
public class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient List<FieldError> fieldErrors;

    public DomainException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    public DomainException(ErrorCode errorCode, String detail, List<FieldError> fieldErrors) {
        super(detail);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static DomainException of(ErrorCode code, String detail) {
        return new DomainException(code, detail);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** Một lỗi theo field cho problem.errors[] (shared/json-schema/v1/problem.schema.json). */
    public record FieldError(String field, String message) {
    }
}
