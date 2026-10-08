package com.platform.productservice.web;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response {@code RetryableUnavailable} của OpenAPI hứa {@code Retry-After} và
 * {@code retryable=true}; hai đường sinh ra 503 phải cùng giữ lời hứa đó.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/products/export");

    @Test
    @DisplayName("CATALOG_UNAVAILABLE ném từ domain (export không chụp được offsets) kèm Retry-After")
    void domainCatalogUnavailableCarriesRetryAfter() {
        request.setRequestURI("/internal/v1/products/export");

        ResponseEntity<ProblemDetail> response = handler.handleDomain(
                DomainException.of(ErrorCode.CATALOG_UNAVAILABLE, "offsets unavailable"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("retryable", true);
    }

    @Test
    @DisplayName("Lock timeout của PostgreSQL kèm Retry-After")
    void transientStorageFailureCarriesRetryAfter() {
        request.setRequestURI("/api/v1/products");

        ResponseEntity<ProblemDetail> response = handler.handleTransient(
                new CannotAcquireLockException("lock timeout"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "CATALOG_UNAVAILABLE");
    }

    @Test
    @DisplayName("Lỗi không retryable không có Retry-After")
    void nonRetryableErrorHasNoRetryAfter() {
        request.setRequestURI("/api/v1/products/x");

        ResponseEntity<ProblemDetail> response = handler.handleDomain(
                DomainException.of(ErrorCode.PRODUCT_NOT_FOUND, "missing"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody().getProperties()).containsEntry("retryable", false);
    }
}
