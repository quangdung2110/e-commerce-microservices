package com.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Order ADR-024: every remote call is timed per dependency and one provider cannot take every worker thread. */
class HttpRemoteResilienceTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger arrived = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            arrived.incrementAndGet();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.getRequestBody().readAllBytes();
            // A body, as every provider contract has: HttpURLConnection reports an error status with an empty
            // body as an I/O error, which HttpRemote deliberately keeps as UNKNOWN rather than a durable rejection.
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        hold.countDown();
        server.stop(0);
    }

    private HttpRemote remote(int maxConcurrent) {
        return new HttpRemote("inventory", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                Duration.ofSeconds(5), new ObjectMapper(), "order-service", meters, maxConcurrent);
    }

    @Test
    void everyCallIsTimedPerDependencyByOutcomeAndStatusClass() {
        HttpRemote remote = remote(4);

        status.set(200);
        remote.get("/x", Map.of());
        status.set(409);
        remote.get("/x", Map.of());
        status.set(503);
        remote.post("/x", Map.of(), null);

        assertThat(meters.get("order.remote.http").tags("dependency", "inventory", "method", "GET",
                "outcome", "ACCEPTED", "status", "2xx").timer().count()).isEqualTo(1);
        assertThat(meters.get("order.remote.http").tags("dependency", "inventory", "method", "GET",
                "outcome", "REJECTED", "status", "4xx").timer().count()).isEqualTo(1);
        assertThat(meters.get("order.remote.http").tags("dependency", "inventory", "method", "POST",
                "outcome", "UNKNOWN", "status", "5xx").timer().count()).isEqualTo(1);
    }

    @Test
    void aSlowProviderCannotTakeMoreThanItsShareOfCallsAndTheRestComeBackUnknown() throws Exception {
        hold = new CountDownLatch(1);
        HttpRemote remote = remote(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RemoteResult> first = pool.submit(() -> remote.get("/slow", Map.of()));
            Future<RemoteResult> second = pool.submit(() -> remote.get("/slow", Map.of()));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (arrived.get() < 2 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(arrived.get()).as("both calls are in flight").isEqualTo(2);

            RemoteResult third = remote.get("/slow", Map.of());

            assertThat(third.isUnknown()).isTrue();
            assertThat(third.errorCode()).isEqualTo("BULKHEAD_FULL");
            assertThat(arrived.get()).as("the third call never reached the provider").isEqualTo(2);

            hold.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).isAccepted()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).isAccepted()).isTrue();
            assertThat(remote.get("/after", Map.of()).isAccepted()).as("slots are released").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRemoteWithoutMetersStillWorks() {
        HttpRemote plain = new HttpRemote("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                Duration.ofSeconds(5), new ObjectMapper(), "order-service");

        assertThat(plain.get("/x", Map.of()).isAccepted()).isTrue();
    }
}
