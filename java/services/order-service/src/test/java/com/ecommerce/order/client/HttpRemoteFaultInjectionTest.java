package com.ecommerce.order.client;

import com.ecommerce.order.client.FaultServer.Fault;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Order does with a provider that fails the way real providers fail (timeout, reset, garbage, a body cut short).
 *
 * <p>The saga's rule is that nothing but a definite answer is a business outcome: every transport failure is UNKNOWN, which the
 * saga resolves by querying or replaying the same command identity. These tests pin that rule against a socket-level fault server
 * and check the three things that make it safe under load: a failure comes back within the configured read timeout, it never
 * leaves a bulkhead permit behind, and the very next call to a healthy provider succeeds.
 */
class HttpRemoteFaultInjectionTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private FaultServer server;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void start() throws Exception {
        server = new FaultServer();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private HttpRemote remote(int maxConcurrent) {
        return new HttpRemote("inventory", server.baseUrl(), Duration.ofMillis(400), READ_TIMEOUT, new ObjectMapper(),
                "order-service", meters, maxConcurrent);
    }

    private static void assertUnknown(RemoteResult result, String codePrefix) {
        assertThat(result.isUnknown()).as("a failure is never a definite answer: %s", result).isTrue();
        assertThat(result.errorCode()).startsWith(codePrefix);
    }

    @Test
    void aProviderThatNeverAnswersIsUnknownWithinTheReadTimeout() {
        server.fault = Fault.HANG;
        long started = System.nanoTime();

        RemoteResult result = remote(4).get("/x", Map.of());

        assertUnknown(result, "TRANSPORT_");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(READ_TIMEOUT.multipliedBy(4));
    }

    @Test
    void aConnectionResetIsUnknown() {
        server.fault = Fault.RESET;

        assertUnknown(remote(4).post("/x", Map.of(), null), "TRANSPORT_");
    }

    @Test
    void aRefusedConnectionIsUnknown() throws Exception {
        int closedPort;
        try (ServerSocket free = new ServerSocket(0)) {
            closedPort = free.getLocalPort();
        }
        HttpRemote remote = new HttpRemote("inventory", "http://127.0.0.1:" + closedPort, Duration.ofMillis(400), READ_TIMEOUT,
                new ObjectMapper(), "order-service", meters, 4);

        assertUnknown(remote.get("/x", Map.of()), "TRANSPORT_");
    }

    @Test
    void bytesThatAreNotHttpAreUnknown() {
        server.fault = Fault.GARBAGE;

        assertUnknown(remote(4).get("/x", Map.of()), "TRANSPORT_");
    }

    @Test
    void aSuccessWhoseBodyIsCutShortIsUnknownNotAnAcceptedAnswerWithNothingInIt() {
        // The saga reads fields from the body of every accepted answer; "accepted, body null" would fail it with a
        // NullPointerException instead of sending it to query or replay the command.
        server.fault = Fault.TRUNCATED_BODY;

        RemoteResult result = remote(4).post("/x", Map.of(), null);

        assertUnknown(result, "BODY_UNREADABLE");
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void aSuccessWhoseBodyIsNotJsonIsUnknown() {
        server.fault = Fault.MALFORMED_JSON;

        assertUnknown(remote(4).get("/x", Map.of()), "BODY_UNREADABLE");
    }

    @Test
    void aBodyThatArrivesSlowerThanTheReadTimeoutIsUnknown() {
        server.fault = Fault.SLOW_BODY;

        assertUnknown(remote(4).get("/x", Map.of()), "BODY_UNREADABLE");
    }

    @Test
    void anEmptySuccessIsStillAccepted() {
        server.fault = Fault.STATUS;
        server.status = 202;
        server.body = "";

        RemoteResult result = remote(4).post("/x", Map.of(), null);

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.body()).isNull();
    }

    @Test
    void overloadAnswersAreUnknownAndCarryTheirCode() {
        server.status = 503;
        server.headers = new String[] {"Retry-After", "7"};
        server.body = "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"retryable\":true}";
        RemoteResult unavailable = remote(4).get("/x", Map.of());

        server.status = 429;
        server.body = "{\"code\":\"RATE_LIMITED\",\"retryable\":true}";
        RemoteResult limited = remote(4).get("/x", Map.of());

        assertThat(unavailable.isUnknown()).isTrue();
        assertThat(unavailable.errorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(limited.isUnknown()).isTrue();
        assertThat(limited.errorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void aDefiniteRefusalIsRejectedNotUnknown() {
        server.status = 422;
        server.body = "{\"code\":\"INSUFFICIENT_STOCK\",\"retryable\":false}";

        RemoteResult result = remote(4).post("/x", Map.of(), null);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.errorCode()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void failuresReleaseTheirBulkheadPermitsAndTheNextCallToAHealthyProviderSucceeds() {
        HttpRemote remote = remote(2);
        for (Fault fault : List.of(Fault.RESET, Fault.GARBAGE, Fault.TRUNCATED_BODY, Fault.HANG, Fault.RESET, Fault.GARBAGE)) {
            server.fault = fault;
            assertThat(remote.get("/x", Map.of()).isUnknown()).as(fault.name()).isTrue();
        }
        server.fault = Fault.STATUS;
        server.status = 200;
        server.body = "{\"ok\":true}";

        RemoteResult healthy = remote.get("/x", Map.of());

        assertThat(healthy.isAccepted()).isTrue();
        assertThat(healthy.body().path("ok").asBoolean()).isTrue();
    }

    @Test
    void aHungProviderTiesUpOnlyItsOwnShareOfCallsWhileTheRestFailFast() throws Exception {
        server.fault = Fault.HANG;
        HttpRemote remote = remote(2);
        ExecutorService callers = Executors.newFixedThreadPool(10);
        try {
            long started = System.nanoTime();
            List<Future<RemoteResult>> results = callers.invokeAll(
                    java.util.Collections.nCopies(10, () -> remote.get("/x", Map.of())));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            long bulkheadFull = results.stream().filter(f -> {
                try {
                    return "BULKHEAD_FULL".equals(f.get().errorCode());
                } catch (Exception e) {
                    return false;
                }
            }).count();
            assertThat(server.requests()).as("only two calls may be in flight to a provider").isLessThanOrEqualTo(2);
            assertThat(bulkheadFull).isGreaterThanOrEqualTo(8);
            assertThat(elapsedMillis).as("the whole burst is bounded by one read timeout, not ten").isLessThan(READ_TIMEOUT.toMillis() * 4);
            for (Future<RemoteResult> future : results) {
                assertThat(future.get().isUnknown()).isTrue();
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void everyFaultIsCountedUnderItsOutcomeAndStatusClass() {
        HttpRemote remote = remote(4);
        server.fault = Fault.RESET;
        remote.get("/x", Map.of());
        server.fault = Fault.STATUS;
        server.status = 503;
        remote.get("/x", Map.of());

        assertThat(meters.get("order.remote.http").tags("dependency", "inventory", "outcome", "UNKNOWN", "status", "none").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("order.remote.http").tags("dependency", "inventory", "outcome", "UNKNOWN", "status", "5xx").timer().count())
                .isEqualTo(1);
    }
}
