package com.platform.promotionservice.infrastructure.product;

import com.platform.promotionservice.common.RequestCorrelation;
import com.platform.promotionservice.config.PromotionProperties;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-002/003 on the wire of the Product snapshot lookup. */
class HttpProductCatalogClientHeadersTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private HttpServer server;
    private final AtomicReference<Headers> received = new AtomicReference<>();
    private HttpProductCatalogClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        PromotionProperties properties = new PromotionProperties();
        properties.getProduct().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getProduct().setTimeout(Duration.ofSeconds(2));
        client = new HttpProductCatalogClient(properties);
    }

    @AfterEach
    void stop() {
        RequestCorrelation.clear();
        server.stop(0);
    }

    @Test
    void passesTheServedRequestContextToProductAndTellsItTheTimeout() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("admin-request-0001", TRACEPARENT, "vendor=a"));

        assertThat(client.exists(UUID.randomUUID())).isTrue();

        Headers headers = received.get();
        assertThat(headers.getFirst("X-Request-Id")).isEqualTo("admin-request-0001");
        assertThat(headers.getFirst("traceparent")).isEqualTo(TRACEPARENT);
        assertThat(headers.getFirst("tracestate")).isEqualTo("vendor=a");
        assertThat(headers.getFirst("X-Request-Timeout-Ms")).isEqualTo("2000");
        assertThat(headers.getFirst("X-Service-Identity")).isEqualTo("promotion-service");
    }

    @Test
    void usesANewRequestIdAndNoTraceparentWhenThereIsNoRequest() {
        assertThat(client.exists(UUID.randomUUID())).isTrue();

        Headers headers = received.get();
        assertThat(headers.getFirst("X-Request-Id")).matches(RequestCorrelation.REQUEST_ID_PATTERN);
        assertThat(headers.getFirst("traceparent")).isNull();
    }
}
