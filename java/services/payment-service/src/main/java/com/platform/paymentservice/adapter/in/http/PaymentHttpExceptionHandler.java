package com.platform.paymentservice.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {PaymentController.class, PaymentQueryController.class,
        PaymentOperationsController.class})
public final class PaymentHttpExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalidRequest(Exception failure, HttpServletRequest request) {
        return problem(400, "VALIDATION_FAILED", false, request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable(DataAccessException failure, HttpServletRequest request) {
        return problem(503, "DEPENDENCY_UNAVAILABLE", true, request);
    }

    private static ResponseEntity<?> problem(int status, String code, boolean retryable,
                                             HttpServletRequest request) {
        String correlation = request.getHeader("X-Correlation-Id");
        int maximum = 128;
        if (correlation == null || correlation.isBlank() || correlation.length() > maximum)
            correlation = "missing-correlation-id";
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", correlation)
                .headers(headers -> RetryAfterHeader.apply(headers, status, retryable))
                .body(new PaymentController.Problem(code, retryable, correlation));
    }
}
