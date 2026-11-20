package com.ecommerce.search.config;

import com.ecommerce.search.common.RequestCorrelation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-002/003 on the read path: trace id in the log context, caller deadline counted. */
class RequestIdFilterTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String REQUEST_ID = "gateway-request-id-0001";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RequestIdFilter filter = new RequestIdFilter(properties(), meters);

    private static SearchProperties properties() {
        SearchProperties properties = Mockito.mock(SearchProperties.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.when(properties.gateway().requestIdHeader()).thenReturn("X-Request-Id");
        return properties;
    }

    @AfterEach
    void cleanUp() {
        RequestCorrelation.clear();
        MDC.clear();
    }

    @Test
    void putsTheTraceIdOfAValidTraceparentInTheLogContextAndKeepsTheRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/search/products");
        request.addHeader("X-Request-Id", REQUEST_ID);
        request.addHeader("traceparent", TRACEPARENT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            traceId.set(MDC.get("trace_id"));
            requestId.set(MDC.get("request_id"));
        });

        assertThat(traceId.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(requestId.get()).isEqualTo(REQUEST_ID);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(REQUEST_ID);
        assertThat(MDC.get("trace_id")).as("cleared after the request").isNull();
        assertThat(RequestCorrelation.current()).isNull();
    }

    @Test
    void leavesTheTraceIdOutWithoutAValidTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("traceparent", "garbage");
        AtomicReference<String> traceId = new AtomicReference<>("unset");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> traceId.set(MDC.get("trace_id")));

        assertThat(traceId.get()).isNull();
    }

    @Test
    void countsARequestThatOutlivesTheCallerDeadline() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/exports/x");
        request.addHeader("X-Request-Timeout-Ms", "5");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/exports/x");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(meters.counter("http.server.caller_deadline_exceeded", "route", "/exports/x").count())
                .isEqualTo(1.0);
    }

    @Test
    void doesNotCountARequestWithoutOrInsideTheDeadline() throws Exception {
        MockHttpServletRequest withoutHeader = new MockHttpServletRequest("GET", "/x");
        MockHttpServletRequest inside = new MockHttpServletRequest("GET", "/x");
        inside.addHeader("X-Request-Timeout-Ms", "60000");

        filter.doFilter(withoutHeader, new MockHttpServletResponse(), (req, res) -> {
        });
        filter.doFilter(inside, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(meters.getMeters()).isEmpty();
    }
}
