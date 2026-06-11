package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error response DTO.
 * Provides structured error information for API responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> errors,
        Instant timestamp,
        String path
) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, Instant.now(), null);
    }

    public static ErrorResponse of(String code, String message, Map<String, String> errors) {
        return new ErrorResponse(code, message, errors, Instant.now(), null);
    }

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, null, Instant.now(), path);
    }

    public static ErrorResponse of(String code, String message, Map<String, String> errors, String path) {
        return new ErrorResponse(code, message, errors, Instant.now(), path);
    }
}
