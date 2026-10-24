package com.ecommerce.pdp.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class ClientTransportsTest {
    @Test
    void startupUsesReadOnlyProductConnectionAndDoesNotDependOnOptionalProviders() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ClientTransports transports = new ClientTransports(meters, Duration.ofSeconds(10), 1);
        try {
            server.stubFor(get(urlPathMatching("/internal/v1/product-details/client-warmup-.+"))
                    .willReturn(aResponse().withStatus(404)));
            registerProduct(transports, "startup-product", server.baseUrl());
            transports.register("inventory", ConnectionProvider.create("startup-inventory"), 300, "http://127.0.0.1:1");
            transports.register("review", ConnectionProvider.create("startup-review"), 300, "http://127.0.0.1:1");
            transports.run(new DefaultApplicationArguments());
            server.verify(1, getRequestedFor(urlPathMatching("/internal/v1/product-details/client-warmup-.+"))
                    .withHeader("X-Service-Identity", equalTo("product-detail-bff")));
            server.verify(0, postRequestedFor(anyUrl()));
            assertThat(meters.get("pdp.client.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
        } finally {
            transports.destroy();
            meters.close();
            server.stop();
        }
    }

    @Test
    void unavailableMandatoryProviderFailsStartupWithinTheConfiguredBudget() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ClientTransports transports = new ClientTransports(meters, Duration.ofMillis(250), 1);
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withFixedDelay(1000).withStatus(503)));
            registerProduct(transports, "startup-unavailable", server.baseUrl());
            long started = System.nanoTime();
            assertThatThrownBy(() -> transports.run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing startup traffic");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(meters.get("pdp.client.startup.warmup").tag("outcome", "failure").timer().count()).isEqualTo(1);
        } finally {
            transports.destroy();
            meters.close();
            server.stop();
        }
    }
    private void registerProduct(ClientTransports transports, String pool, String url) {
        var http = transports.register("product", ConnectionProvider.create(pool), 400, url);
        transports.registerProbe("product", org.springframework.web.reactive.function.client.WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .defaultHeader("X-Service-Identity", "product-detail-bff").baseUrl(url).build());
    }
}
