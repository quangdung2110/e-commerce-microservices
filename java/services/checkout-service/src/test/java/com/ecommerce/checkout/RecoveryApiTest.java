package com.ecommerce.checkout;

import com.ecommerce.checkout.domain.SessionState;
import com.ecommerce.checkout.service.Codec;
import com.ecommerce.checkout.store.SessionStore;
import com.ecommerce.checkout.support.ContractSchemas;
import com.ecommerce.checkout.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** WF-04: recovery after an unknown result, and the status endpoint. */
class RecoveryApiTest extends AbstractCheckoutTest {

    @Autowired
    private SessionStore store;
    @Autowired
    private Codec codec;

    /** A claimed token whose Order POST answered 503: RETRYABLE, with {@code last_order_call_started_at} set. */
    private String retryableToken(String user) {
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(problem(503, "UNAVAILABLE"));
        submit(user, token).expectStatus().isAccepted();
        assertThat(state(token)).isEqualTo(SessionState.RETRYABLE);
        return token;
    }

    private SessionState state(String token) {
        return store.findControl(token).block().orElseThrow().state();
    }

    @Test
    @DisplayName("Status of a token that was never submitted is 404 SUBMISSION_NOT_FOUND")
    void neverSubmitted() {
        String user = newUser();
        String first = previewOk(user).path("checkout_token").asText();
        JsonNode problem = bodyOf(status(user, first).expectStatus().isNotFound());
        assertThat(problem.path("code").asText()).isEqualTo("SUBMISSION_NOT_FOUND");
        assertThat(problem.path("action").asText()).isEqualTo("NONE");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();

        previewOk(user); // supersedes the first one
        assertThat(bodyOf(status(user, first).expectStatus().isNotFound()).path("code").asText())
                .isEqualTo("SUBMISSION_NOT_FOUND");
        status(user, java.util.UUID.randomUUID().toString()).expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Order already holds PAYMENT_READY: status resolves SUCCEEDED with fresh Payment data and sends nothing")
    void resolvesSuccessAfterLostResponse() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet("PAYMENT_READY");

        JsonNode body = bodyOf(status(user, token).expectStatus().isOk());

        assertThat(ContractSchemas.validate("CheckoutSubmissionReady", body)).isEmpty();
        assertThat(body.path("payment").path("client_secret").asText()).isEqualTo("secret-from-get");
        assertThat(state(token)).isEqualTo(SessionState.SUCCEEDED);
        assertThat(orderPosts()).isEqualTo(1);
        assertThat(counter("checkout.recovery", "outcome", "resolved_success")).isPositive();
        status(user, token).expectStatus().isOk();
    }

    @Test
    @DisplayName("Submit again on a RETRYABLE token resolves the same way and is 200, not 201")
    void submitAgainResolves() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet("PAYMENT_READY");

        JsonNode body = bodyOf(submit(user, token).expectStatus().isOk());

        assertThat(body.path("order_id").asText()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(orderPosts()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVENTORY_RESERVATION_REJECTED", "PAYMENT_INITIATION_REJECTED",
            "CHECKOUT_PAYMENT_WINDOW_EXHAUSTED", "ORDER_CANCELLED_DURING_PREPARATION"})
    @DisplayName("Order FAILED with a known code is 409 with the same code and FAILED")
    void orderFailed(String code) {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGetFailure("FAILED", Fixtures.problem(409, code));

        JsonNode problem = bodyOf(status(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(state(token)).isEqualTo(SessionState.FAILED);
        assertThat(bodyOf(status(user, token).expectStatus().isEqualTo(409)).path("code").asText()).isEqualTo(code);
        previewOk(user);
    }

    @Test
    @DisplayName("Order FAILED with another code is 409 SUBMISSION_REJECTED")
    void orderFailedUnknownCode() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGetFailure("FAILED", Fixtures.problem(422, "INVALID_CHECKOUT_SNAPSHOT"));

        assertThat(bodyOf(status(user, token).expectStatus().isEqualTo(409)).path("code").asText())
                .isEqualTo("SUBMISSION_REJECTED");
    }

    @Test
    @DisplayName("Order RECALCULATION_REQUIRED is CONFLICT 409 PROMOTION_RECALCULATION_NEEDED with dropped_promotion")
    void orderRecalculation() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGetFailure("RECALCULATION_REQUIRED", Fixtures.recalculationProblem());

        JsonNode problem = bodyOf(status(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("PROMOTION_RECALCULATION_NEEDED");
        assertThat(problem.path("dropped_promotion").toString())
                .isEqualTo("{\"promotion_id\":\"promo-1\",\"reason\":\"BUDGET_EXHAUSTED\"}");
        assertThat(problem.path("action").asText()).isEqualTo("CHANGE_VOUCHER");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(state(token)).isEqualTo(SessionState.CONFLICT);
    }

    @Test
    @DisplayName("Order still PREPARING keeps the token as it is: 202 PREPARING")
    void orderPreparing() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet("PREPARING");

        JsonNode body = bodyOf(status(user, token).expectStatus().isAccepted().expectHeader().exists("Retry-After"));

        assertThat(body.path("state").asText()).isEqualTo("PREPARING");
        assertThat(state(token)).isEqualTo(SessionState.RETRYABLE);
        assertThat(orderPosts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Order unreachable: 202 RESULT_UNKNOWN, state untouched, never a 410 or a new quotation")
    void orderGetFailure() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(503, "UNAVAILABLE"));

        JsonNode body = bodyOf(status(user, token).expectStatus().isAccepted());

        assertThat(body.path("state").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(state(token)).isEqualTo(SessionState.RETRYABLE);
        assertThat(orderPosts()).isEqualTo(1);
        assertThat(counter("checkout.recovery", "outcome", "unknown")).isPositive();
        preview(user).expectStatus().isEqualTo(409);
    }

    // ------------------------------------------------------------------ Order does not know the token

    @Test
    @DisplayName("Order has nothing but our POST may still be in flight: 202 RESULT_UNKNOWN and no resend")
    void postMayStillBeInFlight() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        clock.advance(Duration.ofSeconds(10)); // inside 15 s mesh timeout + 5 s

        JsonNode body = bodyOf(status(user, token).expectStatus().isAccepted());

        assertThat(body.path("state").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(orderPosts()).isEqualTo(1);
        assertThat(state(token)).isEqualTo(SessionState.RETRYABLE);
    }

    @Test
    @DisplayName("Inside the retry window the identical payload and Idempotency-Key are sent again")
    void resendsIdenticalPayload() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        stubOrderPost(201);
        clock.advance(Duration.ofSeconds(25));

        JsonNode body = bodyOf(status(user, token).expectStatus().isOk());

        assertThat(body.path("payment").path("client_secret").asText()).isEqualTo("secret-from-post");
        assertThat(orderPosts()).isEqualTo(2);
        var posts = posted(ORDER_PATH);
        assertThat(posts.get(1).getBodyAsString()).isEqualTo(posts.get(0).getBodyAsString());
        assertThat(posts.get(1).getHeader("Idempotency-Key")).isEqualTo(token);
        assertThat(posts.get(0).getHeader("Idempotency-Key")).isEqualTo(token);
        assertThat(state(token)).isEqualTo(SessionState.SUCCEEDED);
        assertThat(counter("checkout.recovery", "outcome", "reposted")).isPositive();
    }

    @Test
    @DisplayName("A resend that is rejected again leaves the token RETRYABLE, never a new quotation")
    void resendFailsAgain() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        clock.advance(Duration.ofSeconds(25));

        status(user, token).expectStatus().isAccepted();

        assertThat(orderPosts()).isEqualTo(2);
        assertThat(state(token)).isEqualTo(SessionState.RETRYABLE);
    }

    @Test
    @DisplayName("Past the 2 minute window Order's 404 ends the token as SUBMISSION_NOT_ACCEPTED and unblocks Preview")
    void notAcceptedAfterTheWindow() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        clock.advance(Duration.ofMinutes(3));

        JsonNode problem = bodyOf(status(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("SUBMISSION_NOT_ACCEPTED");
        assertThat(problem.path("action").asText()).isEqualTo("START_NEW_PREVIEW");
        assertThat(orderPosts()).as("no resend after the window").isEqualTo(1);
        assertThat(state(token)).isEqualTo(SessionState.FAILED);
        assertThat(counter("checkout.recovery", "outcome", "not_accepted")).isPositive();
        previewOk(user);
    }

    @Test
    @DisplayName("Concurrent recoverers: exactly one wins the claim and resends")
    void concurrentRecoverers() {
        String user = newUser();
        String token = retryableToken(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        UPSTREAMS.stubFor(WireMock.post(urlEqualTo(ORDER_PATH)).willReturn(json(201,
                Fixtures.orderReady(token, true, "secret-from-post")).withFixedDelay(300)));
        clock.advance(Duration.ofSeconds(25));

        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> status(user, token).returnResult(String.class)
                    .getStatus().value()));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(orderPosts()).as("the first POST plus exactly one resend").isEqualTo(2);
        assertThat(statuses).allMatch(status -> status == 200 || status == 202);
        assertThat(statuses).contains(200);
    }

    // ------------------------------------------------------------------ SUBMITTING

    /** Leaves a token SUBMITTING as a crashed pod would: claimed, nothing else done. */
    private String abandonedClaim(String user) {
        String token = previewOk(user).path("checkout_token").asText();
        SessionStore.Control control = store.findControl(token).block().orElseThrow();
        String payload = codec.encryptPayload("{\"checkout_token\":\"" + token + "\"}");
        assertThat(store.claim(control.ref(), user, "dead-attempt", payload, clock.millis()).block())
                .isInstanceOf(SessionStore.Claim.Claimed.class);
        return token;
    }

    @Test
    @DisplayName("SUBMITTING inside the owner timeout is somebody's live attempt: 202 PREPARING without asking Order")
    void liveAttemptIsPreparing() {
        String user = newUser();
        String token = abandonedClaim(user);

        JsonNode body = bodyOf(status(user, token).expectStatus().isAccepted());
        assertThat(body.path("state").asText()).isEqualTo("PREPARING");
        assertThat(bodyOf(submit(user, token).expectStatus().isAccepted()).path("state").asText())
                .isEqualTo("PREPARING");

        assertThat(orderGets()).isZero();
        assertThat(orderPosts()).isZero();
    }

    @Test
    @DisplayName("SUBMITTING past the owner timeout is recovered: ask Order, then revalidate and resend the stored payload")
    void deadOwnerIsRecovered() {
        String user = newUser();
        String token = abandonedClaim(user);
        stubOrderGet(problem(404, "NOT_FOUND"));
        clock.advance(Duration.ofSeconds(35));

        JsonNode body = bodyOf(status(user, token).expectStatus().isOk());

        assertThat(body.path("order_id").asText()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(orderPosts()).isEqualTo(1);
        var control = store.findControl(token).block().orElseThrow();
        assertThat(control.state()).isEqualTo(SessionState.SUCCEEDED);
        assertThat(control.revalidatedAtMs()).as("revalidation ran before the resend").isNotNull();
    }

    @Test
    @DisplayName("A superseded or expired quotation never reaches Order through status")
    void statusNeverSubmitsAPreviewedToken() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        clock.advance(Duration.ofMinutes(25));

        status(user, token).expectStatus().isNotFound();
        assertThat(orderPosts()).isZero();
        assertThat(orderGets()).isZero();
    }
}
