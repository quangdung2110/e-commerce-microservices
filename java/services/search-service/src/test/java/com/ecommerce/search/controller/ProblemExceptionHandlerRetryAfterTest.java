package com.ecommerce.search.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-005: a retryable 503 tells the caller how long to wait; nothing else does. */
class ProblemExceptionHandlerRetryAfterTest {

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/search/products");

    @Test
    void anUnreachableClusterIsA503WithRetryAfter() {
        ResponseEntity<Object> response = handler.onElasticsearchFailure(new IOException("connection refused"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(ProblemExceptionHandler.RETRY_AFTER_SECONDS));
    }

    @Test
    void aTimeoutIsNotA503AndCarriesNoRetryAfter() {
        ResponseEntity<Object> response = handler.onElasticsearchFailure(new SocketTimeoutException("read timed out"), request);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void anUnexpectedFailureCarriesNoRetryAfter() {
        ResponseEntity<Object> response = handler.onUnexpected(new IllegalStateException("bug"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
