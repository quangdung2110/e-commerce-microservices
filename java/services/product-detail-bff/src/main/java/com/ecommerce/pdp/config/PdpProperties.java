package com.ecommerce.pdp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Cấu hình của BFF (docs/product-detail-bff/operations.md §Cấu hình). Giá trị mặc định
 * nằm trong application.yml và bằng số của ADR-004/ADR-007.
 *
 * <p>Validate lúc khởi động: thiếu cấu hình bắt buộc thì ứng dụng không lên, tức không
 * bao giờ ready, thay vì trả URL media hỏng hay gọi Review chắc chắn bị {@code 401}.
 */
@Validated
@ConfigurationProperties(prefix = "pdp")
public record PdpProperties(
        @NotNull Duration deadline,
        @NotNull Duration phaseMinRemaining,
        @NotNull Duration composeReserve,
        @Valid @NotNull Upstream product,
        @Valid @NotNull Upstream inventory,
        @Valid @NotNull ReviewUpstream review,
        @Valid @NotNull Breaker circuitBreaker,
        @Valid @NotNull Media media,
        @NotBlank String fullResponseCacheControl) {

    /** Một dependency HTTP: timeout trần, bulkhead và ngưỡng call chậm của circuit breaker. */
    public record Upstream(
            boolean enabled,
            String baseUrl,
            @NotNull Duration timeout,
            @Min(1) int bulkhead,
            @NotNull Duration slowCallThreshold) {

        @AssertTrue(message = "base-url must be an absolute http(s) URL when the dependency is enabled")
        public boolean isBaseUrlConfigured() {
            return !enabled || isAbsoluteUrl(baseUrl, false);
        }
    }

    public record ReviewUpstream(
            boolean enabled,
            String baseUrl,
            @NotNull Duration timeout,
            @Min(1) int bulkhead,
            @NotNull Duration slowCallThreshold,
            String serviceToken,
            @Min(1) @Max(5) int previewLimit) {

        @AssertTrue(message = "base-url must be an absolute http(s) URL when Review is enabled")
        public boolean isBaseUrlConfigured() {
            return !enabled || isAbsoluteUrl(baseUrl, false);
        }

        /** Review từ chối mọi request thiếu Bearer token nằm trong REVIEW_INTERNAL_PDP_TOKENS. */
        @AssertTrue(message = "PDP_REVIEW_SERVICE_TOKEN is required when Review is enabled")
        public boolean isServiceTokenConfigured() {
            return !enabled || (serviceToken != null && !serviceToken.isBlank());
        }

        public Upstream asUpstream() {
            return new Upstream(enabled, baseUrl, timeout, bulkhead, slowCallThreshold);
        }
    }

    public record Breaker(
            @NotNull Duration slidingWindow,
            @Min(1) int minimumCalls,
            @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            @DecimalMin("1") @DecimalMax("100") float slowCallRateThreshold,
            @NotNull Duration openDuration,
            @Min(1) int halfOpenCalls) {
    }

    /** URL media = {@code cdnBaseUrl} + storage key (ADR-005); không bao giờ lộ storage key. */
    public record Media(String cdnBaseUrl, String placeholderUrl) {

        @AssertTrue(message = "PDP_MEDIA_CDN_BASE_URL must be an absolute https URL without trailing slash")
        public boolean isCdnBaseUrlValid() {
            return isAbsoluteUrl(cdnBaseUrl, true) && !cdnBaseUrl.endsWith("/");
        }

        @AssertTrue(message = "PDP_MEDIA_PLACEHOLDER_URL must be an absolute https URL")
        public boolean isPlaceholderUrlValid() {
            return isAbsoluteUrl(placeholderUrl, true);
        }
    }

    /** Luôn bật: Product là dependency bắt buộc của PDP. */
    @AssertTrue(message = "Product cannot be disabled")
    public boolean isProductEnabled() {
        return product == null || product.enabled();
    }

    private static boolean isAbsoluteUrl(String value, boolean httpsOnly) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean schemeOk = httpsOnly
                    ? "https".equalsIgnoreCase(scheme)
                    : "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
            return schemeOk && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
