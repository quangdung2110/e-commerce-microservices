package com.ecommerce.checkout;

import com.ecommerce.checkout.resilience.CallResult;
import com.ecommerce.checkout.resilience.Deadline;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.Outcome;
import com.ecommerce.checkout.support.Fixtures;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR-014: deadlines, timeouts shorter than the mesh, no application retry, per-dependency circuits and bulkheads. */
class ResilienceTest extends AbstractCheckoutTest {

    @Test
    @DisplayName("The application never retries: one failing Cart call is one request")
    void noApplicationRetry() {
        stubCart(problem(500, "INTERNAL"));

        preview(newUser()).expectStatus().isEqualTo(503);

        assertThat(requests(getRequestedFor(urlPathMatching(CART_PATH)))).isEqualTo(1);
    }

    @Test
    @DisplayName("Failures open only that dependency's circuit; an open circuit is 503 with Retry-After and no call")
    void circuitsAreIndependent() {
        stubCart(problem(500, "INTERNAL"));
        for (int i = 0; i < 25; i++) {
            preview(newUser()).expectStatus().isEqualTo(503);
        }
        assertThat(guard.isOpen(Dependency.CART)).isTrue();
        assertThat(guard.isOpen(Dependency.PRODUCT)).isFalse();
        assertThat(guard.isOpen(Dependency.INVENTORY)).isFalse();
        assertThat(guard.isOpen(Dependency.ORDER_POST)).isFalse();

        int cartRequests = requests(getRequestedFor(urlPathMatching(CART_PATH)));
        var response = preview(newUser()).expectStatus().isEqualTo(503).expectHeader().exists("Retry-After");
        String retryAfter = response.returnResult(String.class).getResponseHeaders().getFirst("Retry-After");

        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 10);
        assertThat(requests(getRequestedFor(urlPathMatching(CART_PATH)))).isEqualTo(cartRequests);
        assertThat(calls(Dependency.CART, "circuit_open")).isPositive();

        // A healthy Cart and a closed circuit make Preview work again.
        guard.breaker(Dependency.CART).reset();
        stubCart(Fixtures.cart());
        preview(newUser()).expectStatus().isOk();
    }

    @Test
    @DisplayName("A bulkhead rejection is 503 and never counts against the circuit")
    void bulkheadDoesNotOpenTheCircuit() {
        UPSTREAMS.stubFor(WireMock.get(urlPathMatching(CART_PATH)).willReturn(json(200, Fixtures.cart())
                .withFixedDelay(400)));
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            calls.add(CompletableFuture.supplyAsync(() ->
                    preview(newUser()).returnResult(String.class).getStatus().value()));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(statuses).contains(200, 503);
        assertThat(calls(Dependency.CART, "bulkhead_rejected")).isPositive();
        assertThat(guard.isOpen(Dependency.CART)).isFalse();
    }

    @Test
    @DisplayName("With under 200 ms left, no call is sent and the outcome is budget_skipped")
    void budgetSkipsTheWave() {
        AtomicBoolean invoked = new AtomicBoolean();

        CallResult<String> result = guard.call(Dependency.CART, Deadline.startingNow(Duration.ofMillis(150)), () -> {
            invoked.set(true);
            return Mono.just("sent");
        }).block();

        assertThat(result.outcome()).isEqualTo(Outcome.BUDGET_SKIPPED);
        assertThat(invoked).isFalse();
        assertThat(calls(Dependency.CART, "budget_skipped")).isPositive();
    }

    @Test
    @DisplayName("The call timeout is min(ceiling, remaining - reserve): shorter than the deadline, shorter than the mesh")
    void timeoutFollowsTheRemainingDeadline() {
        long started = System.nanoTime();
        CallResult<String> result = guard.call(Dependency.CART, Deadline.startingNow(Duration.ofMillis(450)),
                Mono::<String>never).block();
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(elapsed).as("450 ms deadline - 100 ms reserve = 350 ms, below the 500 ms ceiling")
                .isBetween(300L, 450L);
    }

    @Test
    @DisplayName("A cancelled Preview cancels its dependency calls and writes nothing")
    void previewFollowsClientCancellation() throws Exception {
        UPSTREAMS.stubFor(WireMock.get(urlPathMatching(CART_PATH)).willReturn(json(200, Fixtures.cart())
                .withFixedDelay(300)));
        String user = newUser();
        var raw = org.springframework.web.reactive.function.client.WebClient.create("http://localhost:" + port);
        var subscription = raw.post().uri("/api/v1/checkout/preview").header("X-User-Id", user)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .bodyValue(Fixtures.json(previewBody(null, null))).retrieve().toBodilessEntity()
                .subscribe(ignored -> { }, ignored -> { });
        Thread.sleep(100);
        subscription.dispose();
        Thread.sleep(800);

        assertThat(redis.hasKey("checkout:active:" + user).block()).isFalse();
        assertThat(PRODUCT.calls()).as("Wave 2 never started").isZero();
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;
}
