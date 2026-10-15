package com.ecommerce.order.client;

import com.ecommerce.order.common.RequestCorrelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contracts ADR-002 on the wire: what a provider actually receives from {@link HttpRemote}, on the
 * request thread and off it.
 */
class HttpRemotePropagationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private HttpServer server;
    private final AtomicReference<Headers> received = new AtomicReference<>();
    private HttpRemote remote;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        remote = new HttpRemote("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), new ObjectMapper(), "order-service");
    }

    @AfterEach
    void stop() {
        RequestCorrelation.clear();
        server.stop(0);
    }

    @Test
    void passesTheInboundRequestContextThrough() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("checkout-request-1", TRACEPARENT, "vendor=a"));

        RemoteResult result = remote.get("/internal/x", Map.of());

        assertThat(result.isAccepted()).isTrue();
        Headers headers = received.get();
        assertThat(headers.getFirst("X-Request-Id")).isEqualTo("checkout-request-1");
        assertThat(headers.getFirst("traceparent")).isEqualTo(TRACEPARENT);
        assertThat(headers.getFirst("tracestate")).isEqualTo("vendor=a");
        assertThat(headers.getFirst("X-Service-Identity")).isEqualTo("order-service");
    }

    @Test
    void usesANewRequestIdPerCallAndNeverInventsATraceparentOffTheRequestPath() {
        remote.get("/internal/x", Map.of());
        String first = received.get().getFirst("X-Request-Id");
        remote.get("/internal/x", Map.of());
        String second = received.get().getFirst("X-Request-Id");

        assertThat(first).matches(RequestCorrelation.REQUEST_ID_PATTERN);
        assertThat(second).matches(RequestCorrelation.REQUEST_ID_PATTERN).isNotEqualTo(first);
        assertThat(received.get().getFirst("traceparent")).isNull();
        assertThat(received.get().getFirst("tracestate")).isNull();
    }

    @Test
    void keepsTheBusinessCorrelationHeadersTheCallerSetAndDoesNotDuplicateRequestId() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("checkout-request-1", null, null));

        remote.post("/internal/x", Map.of("X-Correlation-Id", "order-checkout-abc", "x-request-id", "explicit-0001"),
                null);

        Headers headers = received.get();
        assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo("order-checkout-abc");
        assertThat(headers.get("X-Request-Id")).containsExactly("explicit-0001");
        assertThat(headers.getFirst("traceparent")).isNull();
    }

    @Test
    void doesNotForwardTracestateWithoutATraceparent() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("checkout-request-1", "garbage", "vendor=a"));

        remote.get("/internal/x", Map.of());

        assertThat(received.get().getFirst("traceparent")).isNull();
        assertThat(received.get().getFirst("tracestate")).isNull();
    }
}
