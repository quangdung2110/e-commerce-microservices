package com.ecommerce.checkout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration of Checkout (docs/checkout/operations.md, "Cau hinh"). Defaults in
 * application.yml equal the numbers of ADR-014.
 *
 * <p>Validated at startup: a missing key, URL or CDN setting means the application never
 * becomes ready instead of running with a broken dependency (operations.md). The assertions
 * encode CHK-INV-07: every Checkout timeout is shorter than the mesh timeout and every
 * deadline is at least one second shorter than the Gateway wait.
 */
@Validated
@ConfigurationProperties(prefix = "checkout")
public record CheckoutProperties(
        @NotBlank String redisUrl,
        @Valid @NotNull Keys keys,
        @Valid @NotNull Endpoints endpoints,
        @Valid @NotNull Deadlines deadlines,
        @Valid @NotNull Timeouts timeouts,
        @Valid @NotNull Bulkheads bulkheads,
        @Valid @NotNull Breaker circuitBreaker,
        @Valid @NotNull Lifecycle lifecycle,
        @Valid @NotNull Media media,
        @Valid @NotNull RetryAfter retryAfter,
        @DefaultValue("false") boolean voucherOptionsEnabled,
        @NotNull Duration phaseMinRemaining,
        @NotNull Duration dependencyReserve,
        @NotNull Duration orderReserve,
        @NotNull Duration orderMinRemaining,
        @NotNull Duration redisTimeout) {

    /**
     * Versioned secrets, {@code v1:<base64>,v2:<base64>}. HMAC keys are at least 32 bytes and
     * encryption keys exactly 32 bytes (AES-256). Old versions stay until every record that
     * used them has expired (at least 24 hours).
     */
    public record Keys(
            @NotBlank String tokenHmacKeys,
            @NotBlank String tokenHmacActiveVersion,
            @NotBlank String encryptionKeys,
            @NotBlank String encryptionActiveVersion) {
    }

    public record Endpoints(
            String cartBaseUrl,
            String userBaseUrl,
            String productGrpcTarget,
            String inventoryBaseUrl,
            String promotionBaseUrl,
            String orderBaseUrl) {

        @AssertTrue(message = "CHECKOUT_CART_BASE_URL, CHECKOUT_USER_BASE_URL, CHECKOUT_INVENTORY_BASE_URL, "
                + "CHECKOUT_PROMOTION_BASE_URL and CHECKOUT_ORDER_BASE_URL must be absolute http(s) URLs")
        public boolean isHttpEndpointsConfigured() {
            return isAbsoluteUrl(cartBaseUrl, false) && isAbsoluteUrl(userBaseUrl, false)
                    && isAbsoluteUrl(inventoryBaseUrl, false) && isAbsoluteUrl(promotionBaseUrl, false)
                    && isAbsoluteUrl(orderBaseUrl, false);
        }

        @AssertTrue(message = "CHECKOUT_PRODUCT_GRPC_TARGET is required (host:port)")
        public boolean isProductTargetConfigured() {
            return productGrpcTarget != null && !productGrpcTarget.isBlank();
        }
    }

    /** Deadlines count from the moment Checkout receives the request (ADR-014). */
    public record Deadlines(
            @NotNull Duration preview,
            @NotNull Duration voucherOptions,
            @NotNull Duration submit) {
    }

    /** Per-call timeout ceilings; the effective value is {@code min(ceiling, remaining - reserve)}. */
    public record Timeouts(
            @NotNull Duration cart,
            @NotNull Duration user,
            @NotNull Duration product,
            @NotNull Duration inventory,
            @NotNull Duration promotionCalculation,
            @NotNull Duration promotionOptions,
            @NotNull Duration orderPost,
            @NotNull Duration orderGet) {
    }

    public record Bulkheads(
            @Min(1) int cart,
            @Min(1) int user,
            @Min(1) int product,
            @Min(1) int inventory,
            @Min(1) int promotionCalculation,
            @Min(1) int promotionOptions,
            @Min(1) int orderPost,
            @Min(1) int orderGet) {
    }

    public record Breaker(
            @NotNull Duration slidingWindow,
            @Min(1) int minimumCalls,
            @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            @DecimalMin("1") @DecimalMax("100") float slowCallRateThreshold,
            @DecimalMin("0.1") @DecimalMax("1.0") double slowCallTimeoutRatio,
            @NotNull Duration openDuration,
            @Min(1) int halfOpenCalls) {
    }

    /** TTLs and windows of the workflow state (data.md, domain.md). */
    public record Lifecycle(
            @NotNull Duration quoteTtl,
            @NotNull Duration sessionRetention,
            @NotNull Duration previewIdempotencyTtl,
            @NotNull Duration submitOwnerTimeout,
            @NotNull Duration submitRetryWindow,
            @NotNull Duration orderCallInFlightWindow,
            @NotNull Duration successResultTtl) {
    }

    /** Seconds suggested to the client in {@code Retry-After}; the contract allows 1 to 60. */
    public record RetryAfter(
            @Min(1) int preparingSeconds,
            @Min(1) int resultUnknownSeconds,
            @Min(1) int unavailableSeconds) {
    }

    /**
     * URL media = CDN base + storage key; the storage key never leaves Checkout.
     *
     * @param requireHttps only local development sets {@code false}
     */
    public record Media(String cdnBaseUrl, String placeholderUrl, @DefaultValue("true") boolean requireHttps) {

        @AssertTrue(message = "CHECKOUT_MEDIA_CDN_BASE_URL must be an absolute URL (https unless "
                + "CHECKOUT_MEDIA_REQUIRE_HTTPS=false) without trailing slash")
        public boolean isCdnBaseUrlValid() {
            return isAbsoluteUrl(cdnBaseUrl, requireHttps) && !cdnBaseUrl.endsWith("/");
        }

        @AssertTrue(message = "CHECKOUT_MEDIA_PLACEHOLDER_URL must be an absolute URL (https unless "
                + "CHECKOUT_MEDIA_REQUIRE_HTTPS=false)")
        public boolean isPlaceholderUrlValid() {
            return isAbsoluteUrl(placeholderUrl, requireHttps);
        }
    }

    @AssertTrue(message = "every Checkout timeout must be shorter than the mesh timeout (operations.md, Resilience)")
    public boolean isTimeoutsShorterThanMesh() {
        if (timeouts == null) {
            return true;
        }
        return timeouts.cart().compareTo(Duration.ofSeconds(1)) < 0
                && timeouts.user().compareTo(Duration.ofSeconds(2)) < 0
                && timeouts.product().compareTo(Duration.ofSeconds(2)) < 0
                && timeouts.inventory().compareTo(Duration.ofSeconds(4)) < 0
                && timeouts.promotionCalculation().compareTo(Duration.ofSeconds(3)) < 0
                && timeouts.promotionOptions().compareTo(Duration.ofSeconds(2)) < 0
                && timeouts.orderPost().compareTo(Duration.ofSeconds(15)) < 0
                && timeouts.orderGet().compareTo(Duration.ofSeconds(15)) < 0;
    }

    @AssertTrue(message = "every deadline must be at least one second shorter than the 25 s Gateway wait")
    public boolean isDeadlinesShorterThanGateway() {
        if (deadlines == null) {
            return true;
        }
        Duration limit = Duration.ofSeconds(24);
        return deadlines.preview().compareTo(limit) <= 0
                && deadlines.voucherOptions().compareTo(limit) <= 0
                && deadlines.submit().compareTo(limit) <= 0;
    }

    @AssertTrue(message = "CHECKOUT_SUBMIT_OWNER_TIMEOUT must be longer than the Submit deadline")
    public boolean isOwnerTimeoutLongerThanSubmitDeadline() {
        return lifecycle == null || deadlines == null
                || lifecycle.submitOwnerTimeout().compareTo(deadlines.submit()) > 0;
    }

    @AssertTrue(message = "CHECKOUT_SUBMIT_RETRY_WINDOW must be longer than the Order mesh timeout plus 5 s")
    public boolean isRetryWindowLongerThanOrderMeshTimeout() {
        return lifecycle == null || lifecycle.submitRetryWindow().compareTo(Duration.ofSeconds(20)) > 0;
    }

    @AssertTrue(message = "the in-flight window must cover the Order mesh timeout (15 s) plus 5 s")
    public boolean isInFlightWindowCoversOrderMeshTimeout() {
        return lifecycle == null || lifecycle.orderCallInFlightWindow().compareTo(Duration.ofSeconds(20)) >= 0;
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
