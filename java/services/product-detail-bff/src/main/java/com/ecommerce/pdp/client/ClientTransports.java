package com.ecommerce.pdp.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time startup initialization on the actual outbound pools. Read-only probes have their
 * own bounded startup budget; request deadlines, circuits and runtime readiness stay independent.
 * No customer identifiers, tokens or response bodies are logged or used as metric labels.
 */
@Component
public final class ClientTransports implements ApplicationRunner, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ClientTransports.class);
    private static final String ABSENT = "00000000-0000-0000-0000-000000000000";
    private final List<Mono<Void>> initializers = new ArrayList<>();
    private final List<Mono<Void>> probes = new ArrayList<>();
    private final List<ConnectionProvider> pools = new ArrayList<>();
    private final MeterRegistry meters;
    private final Duration budget;
    private final int rounds;

    public ClientTransports(MeterRegistry meters,
                            @Value("${pdp.client-warmup.timeout:30s}") Duration budget,
                            @Value("${pdp.client-warmup.rounds:3}") int rounds) {
        if (budget.isNegative() || budget.isZero() || rounds < 1 || rounds > 10) {
            throw new IllegalArgumentException("Client warm-up needs a positive timeout and 1..10 rounds");
        }
        this.meters = meters;
        this.budget = budget;
        this.rounds = rounds;

    }

    HttpClient register(String name, ConnectionProvider pool, int connectTimeoutMillis, String baseUrl) {
        pools.add(pool);
        HttpClient http = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                // URI is a bounded dependency name, never a customer/token/slug-bearing path.
                .metrics(true, ignored -> name);
        initializers.add(http.warmup());
        return http;
    }

    void registerProbe(String name, WebClient client) {
        String path = probePath(name);
        if (path != null) {
            probes.add(client.get().uri(path)
                    .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                            .flatMap(body -> {
                        int status = response.statusCode().value();
                        return status == 200 || status == 404
                                ? Mono.<Void>empty()
                                : Mono.error(new IllegalStateException("Startup read probe " + name + " HTTP " + status));
                    }))
                    .timeout(Duration.ofSeconds(5)));
        }
    }

    private static String probePath(String name) {
        return name.equals("product") ? "/internal/v1/product-details/client-warmup-" + ABSENT : null;
        // Inventory and Review remain optional; only their local client resources are initialized.
    }

    @Override
    public void run(ApplicationArguments arguments) {
        long started = System.nanoTime();
        List<Mono<Void>> reads = new ArrayList<>(probes);
        try {
            Mono.when(initializers)
                    .thenMany(reactor.core.publisher.Flux.range(0, rounds)
                            .concatMap(round -> Mono.when(reads)
                                    .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                                            .maxBackoff(Duration.ofMillis(500)))))
                    .then().timeout(budget).block();
            Timer.builder("pdp.client.startup.warmup").tag("outcome", "success")
                    .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
            log.info("Outbound client warm-up completed in {} ms ({} read-only rounds)",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), rounds);
        } catch (RuntimeException error) {
            Timer.builder("pdp.client.startup.warmup").tag("outcome", "failure")
                    .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
            throw new IllegalStateException("Outbound client warm-up failed; refusing startup traffic", error);
        }
    }

    @Override
    public void destroy() {
        pools.forEach(ConnectionProvider::dispose);
    }
}

