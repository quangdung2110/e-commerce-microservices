package com.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackendHttpObservabilityTest {
    @Test
    void clientObservationsDoNotCreateSeriesPerBusinessIdentifier() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reservations", exchange -> {
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            HttpRemote remote = new HttpRemote("inventory", "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), new ObjectMapper(), "order-service", meters, 4, observations);
            remote.get("/reservations/private-order-1", Map.of());
            remote.get("/reservations/private-order-2", Map.of());
            var timers = meters.find("http.client.requests").timers();
            assertThat(timers).singleElement().satisfies(timer -> {
                assertThat(timer.count()).isEqualTo(2);
                assertThat(timer.getId().getTag("uri")).isEqualTo("/inventory");
                assertThat(timer.getId().getTags().toString()).doesNotContain("private-order");
            });
        } finally { meters.close(); server.stop(0); }
    }
}
