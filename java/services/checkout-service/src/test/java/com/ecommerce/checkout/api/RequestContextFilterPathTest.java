package com.ecommerce.checkout.api;

import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.config.CheckoutProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The route budget and the request context are chosen from the path Spring routes on: decoded, without
 * {@code ;param}. Checkout authorizes in the controller, so no access decision hangs on this filter, but
 * a request whose raw path differs from its routed path ({@code /%61pi/v1/...}) would otherwise reach the
 * controller with no context at all (a 500) and no {@code Cache-Control: private, no-store}, and
 * {@code /api/v1/checkout/%70review} would get the 20 s Submit budget instead of Preview's 5 s.
 */
class RequestContextFilterPathTest {

    private static final Duration PREVIEW = Duration.ofSeconds(5);
    private static final Duration OPTIONS = Duration.ofMillis(1500);
    private static final Duration SUBMIT = Duration.ofSeconds(20);

    private RequestContextFilter filter;

    @BeforeEach
    void setUp() {
        CheckoutProperties properties = mock(CheckoutProperties.class);
        when(properties.deadlines()).thenReturn(new CheckoutProperties.Deadlines(PREVIEW, OPTIONS, SUBMIT));
        filter = new RequestContextFilter(properties);
    }

    private RequestContext contextOf(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
        filter.filter(exchange, e -> Mono.empty()).block();
        return exchange.getAttribute(RequestContextFilter.CONTEXT_ATTRIBUTE);
    }

    private void assertBudget(String path, Duration budget) {
        RequestContext context = contextOf(path);
        assertThat(context).as("context for " + path).isNotNull();
        assertThat(context.deadline().remaining()).as("remaining for " + path)
                .isLessThanOrEqualTo(budget).isGreaterThan(budget.minusSeconds(1));
    }

    @Test
    @DisplayName("Preview budget applies to the canonical path and to its encoded or parameterised spellings")
    void previewBudgetFollowsTheRoutedPath() {
        for (String path : List.of("/api/v1/checkout/preview", "/%61pi/v1/checkout/preview",
                "/api/v1/checkout/%70review", "/api/v1/checkout/preview;x=1", "/api/v1;x=1/checkout/preview")) {
            assertBudget(path, PREVIEW);
        }
    }

    @Test
    @DisplayName("Voucher-options budget applies to its encoded or parameterised spellings")
    void voucherOptionsBudgetFollowsTheRoutedPath() {
        for (String path : List.of("/api/v1/checkout/previews/tok/voucher-options",
                "/api/v1/checkout/previews/tok/voucher%2Doptions", "/api/v1/checkout/previews/tok/voucher-options;x=1")) {
            assertBudget(path, OPTIONS);
        }
    }

    @Test
    @DisplayName("Other API routes keep the Submit budget; non-API paths get no context")
    void otherRoutes() {
        assertBudget("/api/v1/checkout/submit", SUBMIT);
        assertBudget("/%61pi/v1/checkout/submit", SUBMIT);
        assertThat(contextOf("/actuator/health")).isNull();
    }
}
