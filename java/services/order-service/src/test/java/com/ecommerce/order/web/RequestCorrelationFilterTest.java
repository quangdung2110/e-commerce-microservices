package com.ecommerce.order.web;

import com.ecommerce.order.common.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void cleanUp() {
        RequestCorrelation.clear();
        MDC.clear();
    }

    @Test
    void publishesTheContextDuringTheRequestAndEchoesTheRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        request.addHeader("X-Request-Id", "checkout-request-1");
        request.addHeader("traceparent", TRACEPARENT);
        request.addHeader("tracestate", "vendor=a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestCorrelation> seen = new AtomicReference<>();
        AtomicReference<String> mdcRequest = new AtomicReference<>();
        AtomicReference<String> mdcTrace = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            seen.set(RequestCorrelation.current());
            mdcRequest.set(MDC.get("request_id"));
            mdcTrace.set(MDC.get("trace_id"));
        });

        assertThat(seen.get().requestId()).isEqualTo("checkout-request-1");
        assertThat(seen.get().traceparent()).isEqualTo(TRACEPARENT);
        assertThat(seen.get().tracestate()).isEqualTo("vendor=a");
        assertThat(mdcRequest.get()).isEqualTo("checkout-request-1");
        assertThat(mdcTrace.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("checkout-request-1");
    }

    @Test
    void clearsTheContextAfterTheRequestEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "checkout-request-2");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // The point is what is left on the thread afterwards.
        }

        assertThat(RequestCorrelation.current()).isNull();
        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void replacesAnInvalidRequestIdInsteadOfRejectingTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "no");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Request-Id")).hasSize(36).isNotEqualTo("no");
    }
}
