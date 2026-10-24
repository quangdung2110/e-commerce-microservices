package com.ecommerce.checkout;

import com.ecommerce.checkout.domain.SessionState;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.store.SessionStore;
import com.ecommerce.checkout.support.ContractSchemas;
import com.ecommerce.checkout.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** WF-02 Submit and WF-03 duplicate Submit. */
class SubmitApiTest extends AbstractCheckoutTest {

    @Autowired
    private SessionStore store;

    @Test
    @DisplayName("Submit claims, revalidates, calls Order once with the exact snapshot and answers 201 with fresh Payment data")
    void happyPath() throws Exception {
        String user = newUser();
        String token = previewOk(user, null, Fixtures.PROMOTION_ID).path("checkout_token").asText();

        var response = submit(user, token).expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/checkout/submissions/" + token)
                .expectHeader().valueEquals("Cache-Control", "private, no-store");
        JsonNode body = bodyOf(response);

        assertThat(ContractSchemas.validate("CheckoutSubmissionReady", body)).isEmpty();
        assertThat(body.path("checkout_token").asText()).isEqualTo(token);
        assertThat(body.path("order_id").asText()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(body.path("order_status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.path("payment").path("client_secret").asText()).isEqualTo("secret-from-post");
        assertThat(body.path("payment").path("usable").asBoolean()).isTrue();
        assertThat(orderPosts()).isEqualTo(1);
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.SUCCEEDED);

        var post = posted(ORDER_PATH).get(0);
        assertThat(post.getHeader("Idempotency-Key")).isEqualTo(token);
        assertThat(post.getHeader("X-Service-Identity")).isEqualTo("checkout-service");
        assertThat(post.getHeader("X-Request-Id")).matches("^[A-Za-z0-9._:-]{8,128}$");
        JsonNode payload = requestBody(post);
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "CheckoutOrderSubmissionRequest", payload)).isEmpty();
        assertThat(payload.path("checkout_token").asText()).isEqualTo(token);
        assertThat(payload.path("user_id").asText()).isEqualTo(user);
        assertThat(payload.path("user_ack_degraded_pricing").asBoolean()).isFalse();
        assertThat(payload.path("promotion_campaign_revision").asLong()).isEqualTo(3);
        assertThat(payload.path("promotions").toString()).isEqualTo(
                "[{\"promotion_id\":\"promo-1\",\"discount_amount\":{\"amount_minor\":25000,\"currency\":\"VND\"}}]");
        assertThat(payload.path("pricing").path("final_amount").path("amount_minor").asLong()).isEqualTo(225_000);

        // The hashes follow the Order recipe: RFC 8785 JSON, items sorted by UTF-8 sku_id.
        assertThat(payload.path("items_hash").asText()).isEqualTo(sha256(
                "{\"items\":[{\"quantity\":2,\"sku_id\":\"sku-1\"},{\"quantity\":1,\"sku_id\":\"sku-2\"}]}"));
        assertThat(payload.path("pricing_hash").asText()).isEqualTo(sha256(
                "{\"items\":[{\"sku_id\":\"sku-1\",\"unit_price_minor\":100000},"
                        + "{\"sku_id\":\"sku-2\",\"unit_price_minor\":50000}],"
                        + "\"promotion_campaign_revision\":3,\"total_discount_minor\":25000}"));

        JsonNode first = payload.path("items").get(0);
        assertThat(first.path("sku_id").asText()).isEqualTo("sku-1");
        assertThat(first.path("category_ids").toString()).isEqualTo("[\"cat-root\",\"cat-shirts\"]");
        assertThat(first.path("snapshot_name").asText()).isEqualTo("Ao thun");
        assertThat(first.path("snapshot_image_url").asText())
                .isEqualTo("https://cdn.test/media/products/ao%20thun/black.jpg");
        assertThat(first.path("snapshot_attributes").path("size").asText()).isEqualTo("M");
        assertThat(first.path("variant_name").asText()).isEqualTo("color: black / size: M");
        assertThat(payload.path("items").get(1).path("snapshot_image_url").isNull())
                .as("no image: Order gets null, never the placeholder").isTrue();
        JsonNode delivery = payload.path("delivery_address_snapshot");
        assertThat(delivery.has("address_id")).isFalse();
        assertThat(delivery.has("province_name") || delivery.has("district_name") || delivery.has("ward_name")).isFalse();
        assertThat(delivery.path("recipient_name").asText()).isEqualTo("Nguyen Van A");
        assertThat(payload.path("allocation_plan").path("allocation_plan_id").asText()).isEqualTo("plan-1");
        assertThat(payload.path("allocation_plan").path("lines").get(0).path("warehouse_id").asText()).isEqualTo("wh-1");
    }

    @Test
    @DisplayName("Submit again after success replays 200 with freshly relayed Payment data and no second order")
    void replayRelaysFreshPaymentData() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        submit(user, token).expectStatus().isCreated();

        JsonNode replay = bodyOf(submit(user, token).expectStatus().isOk()
                .expectHeader().valueEquals("Location", "/api/v1/checkout/submissions/" + token));
        assertThat(replay.path("payment").path("client_secret").asText()).isEqualTo("secret-from-get");
        assertThat(replay.path("order_id").asText()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(orderPosts()).isEqualTo(1);

        JsonNode statusBody = bodyOf(status(user, token).expectStatus().isOk());
        assertThat(ContractSchemas.validate("CheckoutSubmissionReady", statusBody)).isEmpty();
        assertThat(orderGets()).isEqualTo(2);
    }

    @Test
    @DisplayName("When the payment attempt is no longer usable the secret and key are null")
    void unusablePaymentHasNoSecret() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        submit(user, token).expectStatus().isCreated();
        stubOrderGet(json(200, Fixtures.orderStatus(token, "PAYMENT_READY", Fixtures.payment(false, null), null)));

        JsonNode replay = bodyOf(submit(user, token).expectStatus().isOk());

        assertThat(replay.path("payment").path("usable").asBoolean()).isFalse();
        assertThat(replay.path("payment").path("client_secret").isNull()).isTrue();
        assertThat(replay.path("payment").path("publishable_key").isNull()).isTrue();
    }

    @Test
    @DisplayName("Concurrent Submits of one token claim once: exactly one Order call and one 201")
    void concurrentSubmitsClaimOnce() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        UPSTREAMS.stubFor(WireMock.post(urlEqualTo(ORDER_PATH)).willReturn(json(201,
                Fixtures.orderReady(token, true, "secret-from-post")).withFixedDelay(300)));

        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(CompletableFuture.supplyAsync(() ->
                    submit(user, token).returnResult(String.class).getStatus().value()));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(orderPosts()).isEqualTo(1);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).allMatch(status -> status == 201 || status == 202 || status == 200);
    }

    // ------------------------------------------------------------------ revalidation

    @Test
    @DisplayName("Name, image and list price changes are not material; the Submit still goes through")
    void cosmeticChangesAreIgnored() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        PRODUCT.put(Fixtures.sku1().setProductName("Ten moi").setImageUrl("new.jpg")
                .setBasePrice(com.ecommerce.contracts.common.v1.Money.newBuilder()
                        .setAmountMinor(999_000).setCurrency("VND")).build());

        submit(user, token).expectStatus().isCreated();
        assertThat(orderPosts()).isEqualTo(1);
    }

    @Test
    @DisplayName("A changed cart is a terminal 409 CART_CHANGED: Order is never called and the replay is identical")
    void cartChanged() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubCart(Fixtures.cart(Fixtures.cartLine(Fixtures.PRODUCT_1, "sku-1", 3),
                Fixtures.cartLine(Fixtures.PRODUCT_2, "sku-2", 1)));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));
        assertThat(problem.path("code").asText()).isEqualTo("CART_CHANGED");
        assertThat(problem.path("changed_fields").toString()).isEqualTo("[\"CART\"]");
        assertThat(problem.path("action").asText()).isEqualTo("START_NEW_PREVIEW");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(orderPosts()).isZero();
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.CONFLICT);

        JsonNode replay = bodyOf(submit(user, token).expectStatus().isEqualTo(409));
        assertThat(replay.path("code").asText()).isEqualTo("CART_CHANGED");
        assertThat(replay.path("changed_fields").toString()).isEqualTo("[\"CART\"]");
        assertThat(orderPosts()).isZero();
        stubHealthy();
        previewOk(user); // a terminal token does not block a new quotation
    }

    @Test
    @DisplayName("A SKU that became unsaleable is 409 SKU_UNAVAILABLE with sku_ids and AVAILABILITY")
    void skuUnavailable() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.markUnavailable("sku-2");

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));
        assertThat(problem.path("code").asText()).isEqualTo("SKU_UNAVAILABLE");
        assertThat(problem.path("sku_ids").toString()).isEqualTo("[\"sku-2\"]");
        assertThat(problem.path("changed_fields").toString()).isEqualTo("[\"AVAILABILITY\"]");
        assertThat(orderPosts()).isZero();
    }

    @Test
    @DisplayName("A changed selling price or category is 409 PRICE_CHANGED")
    void priceOrCategoryChanged() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        PRODUCT.put(Fixtures.sku1().setPrice(com.ecommerce.contracts.common.v1.Money.newBuilder()
                .setAmountMinor(90_000).setCurrency("VND")).build());
        JsonNode price = bodyOf(submit(user, token).expectStatus().isEqualTo(409));
        assertThat(price.path("code").asText()).isEqualTo("PRICE_CHANGED");
        assertThat(price.path("changed_fields").toString()).isEqualTo("[\"PRICE\"]");

        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().addCategoryIds("cat-new").build());
        PRODUCT.put(Fixtures.sku2().build());
        String second = previewOk(user).path("checkout_token").asText();
        PRODUCT.put(Fixtures.sku1().clearCategoryIds().addCategoryIds("cat-other").build());
        JsonNode category = bodyOf(submit(user, second).expectStatus().isEqualTo(409));
        assertThat(category.path("code").asText()).isEqualTo("PRICE_CHANGED");
        assertThat(orderPosts()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("promotionChanges")
    @DisplayName("A different Promotion result is 409 PROMOTION_CHANGED with promotion_reason")
    void promotionChanged(String name, Object calculation, String reason) {
        String user = newUser();
        String token = previewOk(user, null, Fixtures.PROMOTION_ID).path("checkout_token").asText();
        stubPromotionCalculation(calculation);

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("PROMOTION_CHANGED");
        assertThat(problem.path("changed_fields").toString()).isEqualTo("[\"PROMOTION\"]");
        assertThat(problem.path("promotion_reason").asText()).isEqualTo(reason);
        assertThat(problem.path("action").asText()).isEqualTo("CHANGE_VOUCHER");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(orderPosts()).isZero();
    }

    static Stream<Arguments> promotionChanges() {
        return Stream.of(
                Arguments.of("no longer eligible",
                        Fixtures.calculation(false, "VOUCHER_EXPIRED", Fixtures.TOTAL, 0, null), "VOUCHER_EXPIRED"),
                Arguments.of("different discount",
                        Fixtures.calculation(true, "ELIGIBLE", Fixtures.TOTAL, 20_000, 3L), "ELIGIBLE"),
                Arguments.of("different campaign revision",
                        Fixtures.calculation(true, "ELIGIBLE", Fixtures.TOTAL, 25_000, 4L), "ELIGIBLE"));
    }

    @Test
    @DisplayName("Several changes are all listed in changed_fields; the code follows CART > SKU > PRICE > PROMOTION")
    void priorityAndAllChangedFields() {
        String user = newUser();
        String token = previewOk(user, null, Fixtures.PROMOTION_ID).path("checkout_token").asText();
        stubCart(Fixtures.cart(Fixtures.cartLine(Fixtures.PRODUCT_1, "sku-1", 5),
                Fixtures.cartLine(Fixtures.PRODUCT_2, "sku-2", 1)));
        PRODUCT.put(Fixtures.sku1().setPrice(com.ecommerce.contracts.common.v1.Money.newBuilder()
                .setAmountMinor(95_000).setCurrency("VND")).build());
        stubPromotionCalculation(Fixtures.calculation(true, "ELIGIBLE", 240_000, 10_000, 3L));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("CART_CHANGED");
        assertThat(problem.path("changed_fields").toString()).isEqualTo("[\"CART\",\"PRICE\",\"PROMOTION\"]");

        stubHealthy();
        String second = previewOk(user).path("checkout_token").asText();
        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.markUnavailable("sku-2");
        JsonNode sku = bodyOf(submit(user, second).expectStatus().isEqualTo(409));
        assertThat(sku.path("code").asText()).isEqualTo("SKU_UNAVAILABLE");
    }

    @Test
    @DisplayName("A revalidation failure is 503 and RETRYABLE; submitting again revalidates before calling Order")
    void revalidationInfrastructureFailure() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubCart(aFailure(500));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(503));
        assertThat(problem.path("code").asText()).isEqualTo("CHECKOUT_UNAVAILABLE");
        assertThat(orderPosts()).isZero();
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.RETRYABLE);
        assertThat(store.findControl(token).block().orElseThrow().revalidatedAtMs()).isNull();

        stubCart(Fixtures.cart());
        stubOrderGet(problem(404, "NOT_FOUND"));
        submit(user, token).expectStatus().isCreated();

        assertThat(orderPosts()).isEqualTo(1);
        assertThat(requests(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(CART_PATH)))).isEqualTo(3);
    }

    private static ResponseDefinitionBuilder aFailure(int status) {
        return problem(status, "INTERNAL");
    }

    // ------------------------------------------------------------------ Order outcomes

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("orderRejections")
    @DisplayName("Order's terminal outcomes map to the documented public problem and a terminal token")
    void orderRejections(int status, String orderCode, String publicCode, SessionState state, String action) {
        String user = newUser();
        String token = previewOk(user, null, Fixtures.PROMOTION_ID).path("checkout_token").asText();
        Object body = "PROMOTION_RECALCULATION_NEEDED".equals(orderCode)
                ? Fixtures.recalculationProblem() : Fixtures.problem(status, orderCode);
        stubOrderPost(json(status, body));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo(publicCode);
        assertThat(problem.path("action").asText()).isEqualTo(action);
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(problem.toString()).doesNotContain("detail\":\"" + orderCode + " detail");
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(state);
        // Replay: the stored problem, no second Order call.
        JsonNode replay = bodyOf(submit(user, token).expectStatus().isEqualTo(409));
        assertThat(replay.path("code").asText()).isEqualTo(publicCode);
        assertThat(orderPosts()).isEqualTo(1);
    }

    static Stream<Arguments> orderRejections() {
        return Stream.of(
                Arguments.of(409, "PROMOTION_RECALCULATION_NEEDED", "PROMOTION_RECALCULATION_NEEDED",
                        SessionState.CONFLICT, "CHANGE_VOUCHER"),
                Arguments.of(409, "INVENTORY_RESERVATION_REJECTED", "INVENTORY_RESERVATION_REJECTED",
                        SessionState.FAILED, "EDIT_CART"),
                Arguments.of(409, "PAYMENT_INITIATION_REJECTED", "PAYMENT_INITIATION_REJECTED",
                        SessionState.FAILED, "START_NEW_PREVIEW"),
                Arguments.of(409, "CHECKOUT_PAYMENT_WINDOW_EXHAUSTED", "CHECKOUT_PAYMENT_WINDOW_EXHAUSTED",
                        SessionState.FAILED, "START_NEW_PREVIEW"),
                Arguments.of(409, "ORDER_CANCELLED_DURING_PREPARATION", "ORDER_CANCELLED_DURING_PREPARATION",
                        SessionState.FAILED, "START_NEW_PREVIEW"),
                Arguments.of(409, "IDEMPOTENCY_KEY_REUSED", "SUBMISSION_REJECTED", SessionState.FAILED,
                        "START_NEW_PREVIEW"));
    }

    @Test
    @DisplayName("Order 422 (for example INVALID_CHECKOUT_SNAPSHOT) is 409 SUBMISSION_REJECTED")
    void orderUnprocessable() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(json(422, Fixtures.problem(422, "INVALID_CHECKOUT_SNAPSHOT")));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("SUBMISSION_REJECTED");
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.FAILED);
    }

    @ParameterizedTest(name = "Order answers {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 403})
    @DisplayName("Order 400/401/403 mean it never accepted the request: 503 and RETRYABLE, never counted by the circuit")
    void orderConfigurationErrors(int status) {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(problem(status, "STUB"));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(503));

        assertThat(problem.path("code").asText()).isEqualTo("CHECKOUT_UNAVAILABLE");
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.RETRYABLE);
        assertThat(guard.isOpen(Dependency.ORDER_POST)).isFalse();
    }

    @Test
    @DisplayName("Order 503 or a timeout is 202 RESULT_UNKNOWN and the token is RETRYABLE")
    void resultUnknown() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(problem(503, "UNAVAILABLE"));

        var response = submit(user, token).expectStatus().isAccepted()
                .expectHeader().exists("Retry-After")
                .expectHeader().valueEquals("Location", "/api/v1/checkout/submissions/" + token);
        JsonNode body = bodyOf(response);
        assertThat(body.path("state").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(body.path("status_url").asText()).isEqualTo("/api/v1/checkout/submissions/" + token);
        assertThat(ContractSchemas.validate("CheckoutSubmissionPending", body)).isEmpty();
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.RETRYABLE);

        preview(user).expectStatus().isEqualTo(409); // the in-flight guard blocks a new quotation
    }

    @Test
    @DisplayName("Order's own SUBMISSION_IN_PROGRESS means it accepted the request: 202 PREPARING")
    void orderStillPreparing() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(problem(503, "SUBMISSION_IN_PROGRESS"));

        JsonNode body = bodyOf(submit(user, token).expectStatus().isAccepted());

        assertThat(body.path("state").asText()).isEqualTo("PREPARING");
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.RETRYABLE);
    }

    @Test
    @DisplayName("A Submit timeout is 202 RESULT_UNKNOWN well before the mesh would cut the call")
    void orderTimeout() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        UPSTREAMS.stubFor(WireMock.post(urlEqualTo(ORDER_PATH)).willReturn(json(201,
                Fixtures.orderReady(token, true, "late")).withFixedDelay(2500)));

        long started = System.nanoTime();
        JsonNode body = bodyOf(submit(user, token).expectStatus().isAccepted());
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(body.path("state").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(elapsed).isLessThan(2400);
        assertThat(calls(Dependency.ORDER_POST, "timeout")).isPositive();
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    @DisplayName("A client that disconnects after the claim does not cancel the Order call; the result is recorded")
    void clientCancellationAfterClaimStillRecordsTheResult() throws Exception {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        UPSTREAMS.stubFor(WireMock.post(urlEqualTo(ORDER_PATH)).willReturn(json(201,
                Fixtures.orderReady(token, true, "secret-from-post")).withFixedDelay(500)));

        WebClient raw = WebClient.builder().clientConnector(new org.springframework.http.client.reactive
                .ReactorClientHttpConnector(HttpClient.create())).baseUrl(baseUrl()).build();
        Disposable request = raw.post().uri("/api/v1/checkout/submit").header("X-User-Id", user)
                .header("Content-Type", "application/json")
                .bodyValue(Fixtures.json(Fixtures.map("checkout_token", token)))
                .retrieve().toBodilessEntity().subscribe(ignored -> { }, ignored -> { });
        // Wait until Checkout has claimed the token, then drop the connection.
        waitUntil(() -> store.findControl(token).block().orElseThrow().state() == SessionState.SUBMITTING
                || orderPosts() > 0);
        request.dispose();

        waitUntil(() -> store.findControl(token).block().orElseThrow().state() == SessionState.SUCCEEDED);
        assertThat(orderPosts()).isEqualTo(1);
        status(user, token).expectStatus().isOk();
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
            Thread.sleep(25);
        }
    }

    // ------------------------------------------------------------------ ownership and lifecycle

    @Test
    @DisplayName("Another user's token and an unknown token are 404 CHECKOUT_NOT_FOUND; the owner is unaffected")
    void ownership() {
        String owner = newUser();
        String token = previewOk(owner).path("checkout_token").asText();

        JsonNode foreign = bodyOf(submit(newUser(), token).expectStatus().isNotFound());
        assertThat(foreign.path("code").asText()).isEqualTo("CHECKOUT_NOT_FOUND");
        assertThat(foreign.path("action").asText()).isEqualTo("START_NEW_PREVIEW");
        JsonNode unknown = bodyOf(submit(owner, UUID.randomUUID().toString()).expectStatus().isNotFound());
        assertThat(unknown.path("code").asText()).isEqualTo("CHECKOUT_NOT_FOUND");
        status(newUser(), token).expectStatus().isNotFound();
        voucherOptions(newUser(), token).expectStatus().isNotFound();
        assertThat(orderPosts()).isZero();

        submit(owner, token).expectStatus().isCreated();
    }

    @Test
    @DisplayName("A malformed token in the body is 400; in a path it is 404")
    void malformedToken() {
        String user = newUser();
        client.post().uri("/api/v1/checkout/submit").header("X-User-Id", user)
                .header("Content-Type", "application/json").bodyValue("{\"checkout_token\":\"x\"}")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/api/v1/checkout/submit").header("X-User-Id", user)
                .header("Content-Type", "application/json")
                .bodyValue("{\"checkout_token\":\"" + UUID.randomUUID() + "\",\"payment_method\":\"VNPAY\"}")
                .exchange().expectStatus().isBadRequest();
        status(user, "nope").expectStatus().isNotFound();
        voucherOptions(user, "nope").expectStatus().isNotFound();
    }

    @Test
    @DisplayName("A quotation past expires_at is 410 CHECKOUT_EXPIRED and is never claimed")
    void expired() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        clock.advance(Duration.ofMinutes(21));

        JsonNode problem = bodyOf(submit(user, token).expectStatus().isEqualTo(410));

        assertThat(problem.path("code").asText()).isEqualTo("CHECKOUT_EXPIRED");
        assertThat(orderPosts()).isZero();
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.PREVIEWED);
        previewOk(user); // an expired quotation does not block a new one
    }

    @Test
    @DisplayName("An open Order circuit refuses Submit before the claim, leaving the token PREVIEWED")
    void orderCircuitOpenRefusesBeforeClaim() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        guard.breaker(Dependency.ORDER_POST).transitionToOpenState();

        var response = submit(user, token).expectStatus().isEqualTo(503).expectHeader().exists("Retry-After");
        assertThat(bodyOf(response).path("code").asText()).isEqualTo("CHECKOUT_UNAVAILABLE");
        assertThat(store.findControl(token).block().orElseThrow().state()).isEqualTo(SessionState.PREVIEWED);
        assertThat(orderPosts()).isZero();

        guard.breaker(Dependency.ORDER_POST).reset();
        submit(user, token).expectStatus().isCreated();
    }

    private static String sha256(String canonicalJson) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    }
}
