package com.ecommerce.checkout.client;

import com.ecommerce.checkout.support.FakeProduct;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.grpc.Status;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class ClientTransportsTest {
    @Test
    void startupUsesActualHttpPoolsAndGrpcChannelWithoutWrites() {
        verifyStartup(false);
    }

    @Test
    void grpcOutagePreventsStartupSuccessWithinBoundedBudget() {
        verifyStartup(true);
    }

    private void verifyStartup(boolean unavailable) {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        FakeProduct fake = new FakeProduct();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProductClient product = new ProductClient(ManagedChannelBuilder.forAddress("127.0.0.1", fake.port())
                .usePlaintext().build(), Duration.ofSeconds(1), meters);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("product", product);
        ClientTransports transports = new ClientTransports(meters, Duration.ofSeconds(unavailable ? 3 : 10), 1,
                beans.getBeanProvider(ProductClient.class));
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(404)));
            registerRead(transports, "cart", server.baseUrl());
            registerRead(transports, "user", server.baseUrl());
            server.stubFor(get(urlEqualTo("/actuator/health/readiness")).willReturn(aResponse().withStatus(200)));
            registerRead(transports, "promotion", server.baseUrl());
            transports.register("inventory", ConnectionProvider.create("startup-inventory"), 1000, "http://127.0.0.1:1");
            if (unavailable) {
                fake.failWith(Status.UNAVAILABLE);
                assertThatThrownBy(() -> transports.run(new DefaultApplicationArguments()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing startup traffic");
                assertThat(meters.get("checkout.client.startup.warmup").tag("outcome", "failure").timer().count()).isEqualTo(1);
            } else {
                transports.run(new DefaultApplicationArguments());
                assertThat(fake.calls()).isEqualTo(1);
                assertThat(fake.requests()).containsExactly(java.util.List.of("00000000-0000-0000-0000-000000000000"));
                server.verify(1, getRequestedFor(urlPathMatching("/internal/v1/carts/.+/checkout-snapshot")));
                server.verify(1, getRequestedFor(urlPathMatching("/internal/v1/users/.+/addresses/.+/checkout-snapshot")));
                server.verify(0, postRequestedFor(anyUrl()));
                server.verify(1, getRequestedFor(urlEqualTo("/actuator/health/readiness")));
            }
        } finally {
            transports.destroy();
            product.destroy();
            fake.stop();
            meters.close();
            server.stop();
        }
    }
    private void registerRead(ClientTransports transports, String name, String url) {
        var http = transports.register(name, ConnectionProvider.create("startup-" + name), 1000, url);
        transports.registerProbe(name, org.springframework.web.reactive.function.client.WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .defaultHeader("X-Service-Identity", "checkout-service").baseUrl(url).build());
    }
}
