package com.ecommerce.checkout;

import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.support.ContractSchemas;
import com.ecommerce.checkout.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** WF-01 Preview, end to end through HTTP with a real Redis and contract-shaped providers. */
class PreviewApiTest extends AbstractCheckoutTest {

    @Test
    @DisplayName("Preview returns the quotation: sorted lines, CDN or placeholder image, list price, no internal data")
    void happyPath() {
        String user = newUser();
        var response = preview(user).expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "private, no-store")
                .expectHeader().contentTypeCompatibleWith("application/json");
        JsonNode body = bodyOf(response);

        assertThat(ContractSchemas.validate("CheckoutPreview", body)).isEmpty();
        assertThat(UUID.fromString(body.path("checkout_token").asText()).version()).isEqualTo(4);
        assertThat(Instant.parse(body.path("expires_at").asText()))
                .isBetween(Instant.now().plus(Duration.ofMinutes(19)), Instant.now().plus(Duration.ofMinutes(21)));
        assertThat(body.path("items")).hasSize(2);
        assertThat(body.path("items").get(0).path("sku_id").asText()).isEqualTo("sku-1");
        assertThat(body.path("items").get(0).path("line_reference").asText()).isEqualTo("sku-1");
        assertThat(body.path("items").get(0).path("image_url").asText())
                .isEqualTo("https://cdn.test/media/products/ao%20thun/black.jpg");
        assertThat(body.path("items").get(1).path("image_url").asText()).isEqualTo("https://cdn.test/placeholder.png");
        assertThat(body.path("items").get(0).path("list_unit_price").path("amount_minor").asLong()).isEqualTo(120_000);
        assertThat(body.path("items").get(0).path("unit_price").path("amount_minor").asLong()).isEqualTo(100_000);
        assertThat(body.path("items").get(0).path("line_total").path("amount_minor").asLong()).isEqualTo(200_000);
        assertThat(body.path("items").get(0).path("variant_attributes").path("color").asText()).isEqualTo("black");
        assertThat(body.path("promotion").path("promotion_id").isNull()).isTrue();
        assertThat(body.path("promotion").path("campaign_revision").asLong()).isZero();
        assertThat(body.path("pricing").path("total_amount").path("amount_minor").asLong()).isEqualTo(250_000);
        assertThat(body.path("pricing").path("total_discount").path("amount_minor").asLong()).isZero();
        assertThat(body.path("pricing").path("final_amount").path("amount_minor").asLong()).isEqualTo(250_000);

        String text = body.toString();
        assertThat(text).doesNotContain("warehouse_id", "allocation_plan_id", "category_ids", "catalog_revision",
                "items_hash", "pricing_hash", "cat-root");
        assertThat(requests(postRequestedFor(urlEqualTo(PROMOTION_CALC_PATH)))).isZero();
    }

    @Test
    @DisplayName("Every provider receives the identity, request-id and tracing headers the contracts require")
    void dependencyHeaders() {
        String user = newUser();
        String idempotencyKey = UUID.randomUUID().toString();
        preview(user, idempotencyKey, previewBody(null, null)).expectStatus().isOk();

        LoggedRequest cart = UPSTREAMS.findAll(getRequestedFor(urlPathMatching(CART_PATH))).get(0);
        assertThat(cart.getHeader("X-Service-Identity")).isEqualTo("checkout-service");
        assertThat(cart.getHeader("X-Request-Id")).matches("^[A-Za-z0-9._:-]{8,128}$");
        assertThat(cart.getHeader("traceparent")).isNotBlank();
        assertThat(cart.getUrl()).contains(user);

        LoggedRequest inventory = posted(INVENTORY_PATH).get(0);
        assertThat(inventory.getHeader("X-Service-Identity")).isEqualTo("checkout-service");
        assertThat(inventory.getHeader("X-Correlation-ID")).matches("^[A-Za-z0-9._:-]{8,128}$");
        assertThat(inventory.getHeader("X-Causation-ID")).isEqualTo(idempotencyKey);
        assertThat(inventory.containsHeader("Idempotency-Key")).isFalse();
        assertThat(inventory.containsHeader("Authorization")).isFalse();
        JsonNode lines = requestBody(inventory).path("lines");
        assertThat(lines.get(0).path("line_reference").asText()).isEqualTo("sku-1");
        assertThat(requestBody(inventory).has("address")).isFalse();

        assertThat(PRODUCT.header("x-service-identity")).isEqualTo("checkout-service");
        assertThat(PRODUCT.header("x-request-id")).isNotBlank();
        assertThat(PRODUCT.header("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
        assertThat(PRODUCT.requests().get(0)).containsExactlyInAnyOrder("sku-1", "sku-2");
    }

    @Test
    @DisplayName("An invalid X-Request-Id never reaches Inventory or Order; it is replaced by the trace id")
    void invalidRequestIdIsReplaced() {
        String user = newUser();
        client.post().uri("/api/v1/checkout/preview").header("X-User-Id", user)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Request-Id", "x")
                .header("Content-Type", "application/json").bodyValue(Fixtures.json(previewBody(null, null)))
                .exchange().expectStatus().isOk();

        String forwarded = posted(INVENTORY_PATH).get(0).getHeader("X-Correlation-ID");
        assertThat(forwarded).isNotEqualTo("x").matches("^[A-Za-z0-9._:-]{8,128}$");
    }

    @Test
    @DisplayName("A voucher is calculated by Promotion with a context built from Cart quantities and Product facts")
    void withPromotion() {
        String user = newUser();
        JsonNode body = previewOk(user, null, Fixtures.PROMOTION_ID);

        assertThat(ContractSchemas.validate("CheckoutPreview", body)).isEmpty();
        assertThat(body.path("promotion").path("promotion_id").asText()).isEqualTo("promo-1");
        assertThat(body.path("promotion").path("campaign_revision").asLong()).isEqualTo(3);
        assertThat(body.path("promotion").path("discount").path("amount_minor").asLong()).isEqualTo(25_000);
        assertThat(body.path("pricing").path("total_discount").path("amount_minor").asLong()).isEqualTo(25_000);
        assertThat(body.path("pricing").path("final_amount").path("amount_minor").asLong()).isEqualTo(225_000);

        JsonNode request = requestBody(posted(PROMOTION_CALC_PATH).get(0));
        assertThat(request.path("user_id").asText()).isEqualTo(user);
        assertThat(request.has("user_tier")).isFalse();
        assertThat(request.path("promotion_id").asText()).isEqualTo("promo-1");
        JsonNode context = request.path("context");
        assertThat(context.path("subtotal").path("amount_minor").asLong()).isEqualTo(250_000);
        assertThat(context.path("payment_method").asText()).isEqualTo("STRIPE");
        assertThat(context.path("checkout_channel").asText()).isEqualTo("CHECKOUT");
        assertThat(context.path("items").get(0).path("sku_id").asText()).isEqualTo("sku-1");
        assertThat(context.path("items").get(0).path("unit_price").path("amount_minor").asLong()).isEqualTo(100_000);
        assertThat(context.path("items").get(0).path("category_ids").toString())
                .isEqualTo("[\"cat-root\",\"cat-shirts\"]");
    }

    @Test
    @DisplayName("Inventory and Promotion run in parallel, not one after the other")
    void wave3IsParallel() {
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(INVENTORY_PATH))
                .willReturn(json(200, Fixtures.allocatable()).withFixedDelay(500)));
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PROMOTION_CALC_PATH))
                .willReturn(json(200, Fixtures.eligibleCalculation()).withFixedDelay(500)));
        String user = newUser();
        preview(user).expectStatus().isOk(); // warm-up
        long started = System.nanoTime();
        previewOk(user, null, Fixtures.PROMOTION_ID);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).as("two 500 ms calls in parallel").isLessThan(950);
    }

    // ------------------------------------------------------------------ business rejections

    @Test
    @DisplayName("An empty cart is 422 CART_EMPTY and nothing else is called")
    void emptyCart() {
        stubCart(Fixtures.cart());
        stubCart(Fixtures.map("items", List.of()));
        var response = preview(newUser()).expectStatus().isEqualTo(422);
        JsonNode problem = bodyOf(response);

        assertThat(problem.path("code").asText()).isEqualTo("CART_EMPTY");
        assertThat(problem.path("action").asText()).isEqualTo("EDIT_CART");
        assertThat(problem.path("retryable").asBoolean()).isFalse();
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(PRODUCT.calls()).isZero();
        assertThat(posted(INVENTORY_PATH)).isEmpty();
    }

    @Test
    @DisplayName("A SKU that is missing, inactive or whose product_id differs from Cart is 422 SKU_UNAVAILABLE with sku_ids")
    void unavailableSkus() {
        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.markUnavailable("sku-2");
        JsonNode problem = bodyOf(preview(newUser()).expectStatus().isEqualTo(422));
        assertThat(problem.path("code").asText()).isEqualTo("SKU_UNAVAILABLE");
        assertThat(problem.path("sku_ids").toString()).isEqualTo("[\"sku-2\"]");

        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.put(Fixtures.sku2().setProductId("33333333-3333-4333-8333-333333333333").build());
        problem = bodyOf(preview(newUser()).expectStatus().isEqualTo(422));
        assertThat(problem.path("code").asText()).isEqualTo("SKU_UNAVAILABLE");
        assertThat(problem.path("sku_ids").toString()).isEqualTo("[\"sku-2\"]");

        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.put(Fixtures.sku2().setStatus(com.ecommerce.contracts.product.v1.SkuStatus.SKU_STATUS_INACTIVE).build());
        problem = bodyOf(preview(newUser()).expectStatus().isEqualTo(422));
        assertThat(problem.path("sku_ids").toString()).isEqualTo("[\"sku-2\"]");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
    }

    @Test
    @DisplayName("An address User does not own is 422 ADDRESS_NOT_FOUND")
    void addressNotFound() {
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching(USER_PATH))
                .willReturn(problem(404, "ADDRESS_NOT_FOUND")));
        JsonNode problem = bodyOf(preview(newUser()).expectStatus().isEqualTo(422));
        assertThat(problem.path("code").asText()).isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(problem.path("action").asText()).isEqualTo("CHANGE_ADDRESS");
        assertThat(calls(Dependency.USER, "business_reject")).isPositive();
    }

    @Test
    @DisplayName("REJECT_ALL is 422 INSUFFICIENT_STOCK with rejected_lines")
    void insufficientStock() {
        stubInventory(Fixtures.rejectAll("sku-1", 2));
        JsonNode problem = bodyOf(preview(newUser()).expectStatus().isEqualTo(422));

        assertThat(problem.path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(problem.path("rejected_lines").toString()).isEqualTo("[{\"sku_id\":\"sku-1\",\"quantity\":2}]");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONDITIONS_NOT_MET", "MIN_ORDER_NOT_MET", "VOUCHER_NOT_OWNED", "VOUCHER_EXPIRED",
            "ALREADY_USED_VOUCHER", "CAMPAIGN_NOT_ACTIVE", "PROMOTION_DISABLED", "BUDGET_EXHAUSTED"})
    @DisplayName("eligible=false is 422 PROMOTION_INVALID relaying the reason")
    void promotionNotEligible(String reason) {
        stubPromotionCalculation(Fixtures.calculation(false, reason, Fixtures.TOTAL, 0, null));
        JsonNode problem = bodyOf(preview(newUser(), UUID.randomUUID().toString(),
                previewBody(null, Fixtures.PROMOTION_ID)).expectStatus().isEqualTo(422));

        assertThat(problem.path("code").asText()).isEqualTo("PROMOTION_INVALID");
        assertThat(problem.path("promotion_reason").asText()).isEqualTo(reason);
        assertThat(problem.path("action").asText()).isEqualTo("CHANGE_VOUCHER");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
    }

    @Test
    @DisplayName("A reason this build does not know becomes UNKNOWN")
    void unknownPromotionReason() {
        stubPromotionCalculation(Fixtures.calculation(false, "SOMETHING_NEW", Fixtures.TOTAL, 0, null));
        JsonNode problem = bodyOf(preview(newUser(), UUID.randomUUID().toString(),
                previewBody(null, Fixtures.PROMOTION_ID)).expectStatus().isEqualTo(422));
        assertThat(problem.path("promotion_reason").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("A discount that leaves less than 1 VND is 422 FINAL_AMOUNT_NOT_PAYABLE")
    void finalAmountNotPayable() {
        stubPromotionCalculation(Fixtures.calculation(true, "ELIGIBLE", Fixtures.TOTAL, Fixtures.TOTAL, 3L));
        JsonNode problem = bodyOf(preview(newUser(), UUID.randomUUID().toString(),
                previewBody(null, Fixtures.PROMOTION_ID)).expectStatus().isEqualTo(422));

        assertThat(problem.path("code").asText()).isEqualTo("FINAL_AMOUNT_NOT_PAYABLE");
        assertThat(problem.path("action").asText()).isEqualTo("CHANGE_VOUCHER");
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    @DisplayName("Promotion's subtotal differing from Checkout's total is a contract violation, never 'no discount'")
    void promotionSubtotalMismatch() {
        stubPromotionCalculation(Fixtures.calculation(true, "ELIGIBLE", 999, 100, 3L));
        JsonNode problem = bodyOf(preview(newUser(), UUID.randomUUID().toString(),
                previewBody(null, Fixtures.PROMOTION_ID)).expectStatus().isEqualTo(503));

        assertThat(problem.path("code").asText()).isEqualTo("CHECKOUT_UNAVAILABLE");
        assertThat(calls(Dependency.PROMOTION_CALCULATION, "contract_violation")).isPositive();
    }

    @Test
    @DisplayName("An Inventory plan that misses a line, or a Product response that breaks its contract, is 503")
    void contractViolationsAreUnavailable() {
        stubInventory(Fixtures.map("outcome", "ALLOCATABLE", "allocation_plan_id", "p", "generated_at",
                "2026-10-07T00:00:00Z", "lines", List.of(Fixtures.plannedLine("sku-1", 2, "wh-1"))));
        preview(newUser()).expectStatus().isEqualTo(503);
        assertThat(calls(Dependency.INVENTORY, "contract_violation")).isPositive();

        stubInventory(Fixtures.allocatable());
        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.put(Fixtures.sku2().setPrice(com.ecommerce.contracts.common.v1.Money.newBuilder()
                .setAmountMinor(60_000).setCurrency("VND")).build()); // price above base_price 50 000
        preview(newUser()).expectStatus().isEqualTo(503);

        PRODUCT.reset();
        PRODUCT.put(Fixtures.sku1().setProductId("not-a-uuid").build());
        PRODUCT.put(Fixtures.sku2().build());
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching(CART_PATH))
                .willReturn(json(200, Fixtures.cart(Fixtures.cartLine("not-a-uuid", "sku-1", 2),
                        Fixtures.cartLine(Fixtures.PRODUCT_2, "sku-2", 1)))));
        preview(newUser()).expectStatus().isEqualTo(503);
        assertThat(calls(Dependency.PRODUCT, "contract_violation")).isEqualTo(2);
    }

    @Test
    @DisplayName("A dependency failure is 503 with Retry-After, creates no quotation and leaves the old one usable")
    void dependencyFailureChangesNothing() {
        String user = newUser();
        JsonNode first = previewOk(user);

        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(INVENTORY_PATH))
                .willReturn(problem(503, "INVENTORY_UNAVAILABLE")));
        var failed = preview(user).expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After");
        JsonNode problem = bodyOf(failed);
        assertThat(problem.path("code").asText()).isEqualTo("CHECKOUT_UNAVAILABLE");
        assertThat(problem.path("retryable").asBoolean()).isTrue();
        assertThat(problem.path("action").asText()).isEqualTo("RETRY");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();

        // The first quotation was not superseded by the failed Preview.
        stubInventory(Fixtures.allocatable());
        stubOrderPost(201);
        submit(user, first.path("checkout_token").asText()).expectStatus().isCreated();
    }

    @Test
    @DisplayName("A timeout is 503 and the Checkout timeout fires before the mesh would")
    void timeout() {
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching(CART_PATH))
                .willReturn(json(200, Fixtures.cart()).withFixedDelay(900)));
        long started = System.nanoTime();
        preview(newUser()).expectStatus().isEqualTo(503);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsed).as("Cart budget is 500 ms, below the 1 s mesh timeout").isLessThan(900);
        assertThat(calls(Dependency.CART, "timeout")).isPositive();
    }

    @Test
    @DisplayName("400/401/403 from a provider are 503 but never count towards the circuit")
    void clientErrorsDoNotOpenTheCircuit() {
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching(CART_PATH))
                .willReturn(problem(403, "FORBIDDEN")));
        for (int i = 0; i < 25; i++) {
            preview(newUser()).expectStatus().isEqualTo(503);
        }
        assertThat(guard.isOpen(Dependency.CART)).isFalse();
        assertThat(calls(Dependency.CART, "4xx")).isGreaterThanOrEqualTo(25);
        assertThat(calls(Dependency.CART, "circuit_open")).isZero();
    }

    @Test
    @DisplayName("Product gRPC failures are 503; UNAVAILABLE is a connection error")
    void productFailures() {
        PRODUCT.failWith(Status.UNAVAILABLE);
        preview(newUser()).expectStatus().isEqualTo(503);
        assertThat(calls(Dependency.PRODUCT, "connect_error")).isPositive();

        PRODUCT.failWith(Status.PERMISSION_DENIED);
        preview(newUser()).expectStatus().isEqualTo(503);
        assertThat(calls(Dependency.PRODUCT, "4xx")).isPositive();
    }

    // ------------------------------------------------------------------ request validation

    @Test
    @DisplayName("Missing identity is 401; bad header, body or unknown field is 400 INVALID_REQUEST")
    void requestValidation() {
        var unauthenticated = preview(null, UUID.randomUUID().toString(), previewBody(null, null))
                .expectStatus().isUnauthorized();
        assertThat(bodyOf(unauthenticated).path("code").asText()).isEqualTo("UNAUTHENTICATED");

        JsonNode missingKey = bodyOf(preview(newUser(), null, previewBody(null, null)).expectStatus().isBadRequest());
        assertThat(missingKey.path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(ContractSchemas.validate("CheckoutProblem", missingKey)).isEmpty();

        preview(newUser(), "not-a-uuid", previewBody(null, null)).expectStatus().isBadRequest();
        preview(newUser(), UUID.randomUUID().toString(), Fixtures.map("delivery_address_id", "nope"))
                .expectStatus().isBadRequest();
        preview(newUser(), UUID.randomUUID().toString(), Fixtures.map()).expectStatus().isBadRequest();

        // The client may only send identifiers: quantity, price, discount and payment method are refused.
        for (String field : List.of("quantity", "unit_price", "discount", "payment_method", "user_id")) {
            JsonNode problem = bodyOf(preview(newUser(), UUID.randomUUID().toString(),
                    Fixtures.map("delivery_address_id", Fixtures.ADDRESS_ID, field, 1)).expectStatus().isBadRequest());
            assertThat(problem.path("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(totalRequests()).as("a rejected request never fans out").isZero();
    }

    // ------------------------------------------------------------------ idempotency, supersede, fence

    @Test
    @DisplayName("Same Idempotency-Key and body replays the stored response without calling any provider")
    void idempotentReplay() {
        String user = newUser();
        String key = UUID.randomUUID().toString();
        JsonNode first = bodyOf(preview(user, key, previewBody(null, null)).expectStatus().isOk());
        int before = totalRequests();
        int productCalls = PRODUCT.calls();

        JsonNode replay = bodyOf(preview(user, key, previewBody(null, null)).expectStatus().isOk());

        assertThat(replay).isEqualTo(first);
        assertThat(totalRequests()).isEqualTo(before);
        assertThat(PRODUCT.calls()).isEqualTo(productCalls);
        assertThat(counter("checkout.preview.cas", "result", "idempotent_replay")).isPositive();
    }

    @Test
    @DisplayName("Same Idempotency-Key with a different body is 409 IDEMPOTENCY_KEY_REUSED")
    void idempotencyMismatch() {
        String user = newUser();
        String key = UUID.randomUUID().toString();
        preview(user, key, previewBody(null, null)).expectStatus().isOk();

        JsonNode problem = bodyOf(preview(user, key, previewBody(null, Fixtures.PROMOTION_ID))
                .expectStatus().isEqualTo(409));
        assertThat(problem.path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(problem.path("action").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("A fresh Preview supersedes the current PREVIEWED token, which then answers 410")
    void freshPreviewSupersedes() {
        String user = newUser();
        String first = previewOk(user).path("checkout_token").asText();
        String second = previewOk(user).path("checkout_token").asText();
        assertThat(second).isNotEqualTo(first);

        JsonNode gone = bodyOf(submit(user, first).expectStatus().isEqualTo(410));
        assertThat(gone.path("code").asText()).isEqualTo("CHECKOUT_SUPERSEDED");
        assertThat(gone.path("action").asText()).isEqualTo("START_NEW_PREVIEW");
        assertThat(orderPosts()).isZero();
        submit(user, second).expectStatus().isCreated();
    }

    @Test
    @DisplayName("previous_checkout_token equal to the current token replaces it; a stale one is 409 ACTIVE_CHECKOUT_CHANGED")
    void previousTokenFence() {
        String user = newUser();
        String first = previewOk(user).path("checkout_token").asText();

        String second = previewOk(user, first, Fixtures.PROMOTION_ID).path("checkout_token").asText();
        assertThat(second).isNotEqualTo(first);

        JsonNode problem = bodyOf(preview(user, UUID.randomUUID().toString(), previewBody(first, null))
                .expectStatus().isEqualTo(409));
        assertThat(problem.path("code").asText()).isEqualTo("ACTIVE_CHECKOUT_CHANGED");
        assertThat(problem.path("action").asText()).isEqualTo("START_NEW_PREVIEW");

        // The refused Preview did not disturb the current quotation.
        submit(user, second).expectStatus().isCreated();
    }

    @Test
    @DisplayName("A token that is not the user's current quotation as previous is refused even when it never existed")
    void unknownPreviousToken() {
        String user = newUser();
        previewOk(user);
        JsonNode problem = bodyOf(preview(user, UUID.randomUUID().toString(),
                previewBody(UUID.randomUUID().toString(), null)).expectStatus().isEqualTo(409));
        assertThat(problem.path("code").asText()).isEqualTo("ACTIVE_CHECKOUT_CHANGED");
    }

    @Test
    @DisplayName("Concurrent Previews leave exactly one PREVIEWED token for the user")
    void concurrentPreviews() {
        String user = newUser();
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> previewOk(user)));
        }
        List<String> tokens = futures.stream().map(CompletableFuture::join)
                .map(body -> body.path("checkout_token").asText()).toList();
        assertThat(tokens).doesNotHaveDuplicates();

        long previewed = tokens.stream().filter(token -> {
            var response = submitStatusProbe(user, token);
            return response;
        }).count();
        assertThat(previewed).as("tokens that can still be submitted").isEqualTo(1);
    }

    /** {@code true} when the token is still the user's PREVIEWED quotation: voucher options accept only that. */
    private boolean submitStatusProbe(String user, String token) {
        stubPromotionOptions(Fixtures.voucherOptions(List.of()));
        int status = voucherOptions(user, token).returnResult(String.class).getStatus().value();
        return status == 200;
    }

    @Test
    @DisplayName("Preview while the user's token is SUBMITTING or RETRYABLE is 409 SUBMISSION_IN_PROGRESS and calls nothing")
    void inFlightGuard() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(aResponseWithDelay());
        submit(user, token).expectStatus().isEqualTo(202); // Order timed out: the token is RETRYABLE

        int before = totalRequests();
        int productCalls = PRODUCT.calls();
        JsonNode problem = bodyOf(preview(user).expectStatus().isEqualTo(409));

        assertThat(problem.path("code").asText()).isEqualTo("SUBMISSION_IN_PROGRESS");
        assertThat(problem.path("action").asText()).isEqualTo("VIEW_SUBMISSION");
        assertThat(problem.path("status_url").asText()).isEqualTo("/api/v1/checkout/submissions/" + token);
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();
        assertThat(totalRequests()).isEqualTo(before);
        assertThat(PRODUCT.calls()).isEqualTo(productCalls);
        assertThat(counter("checkout.preview.cas", "result", "in_flight_blocked")).isPositive();
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder aResponseWithDelay() {
        return json(201, Fixtures.orderReady("ignored", true, "s")).withFixedDelay(3000);
    }

    @Test
    @DisplayName("After a terminal result a new Preview is allowed")
    void previewAfterTerminal() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        submit(user, token).expectStatus().isCreated();

        JsonNode next = previewOk(user);
        assertThat(next.path("checkout_token").asText()).isNotEqualTo(token);
    }

    @Test
    @DisplayName("Redis holds no raw token, idempotency key, address or secret")
    void redisHoldsNoPlaintext() {
        String user = newUser();
        String key = UUID.randomUUID().toString();
        JsonNode body = bodyOf(preview(user, key, previewBody(null, null)).expectStatus().isOk());
        String token = body.path("checkout_token").asText();
        submit(user, token).expectStatus().isCreated();

        assertThat(redisKeys(token)).isEmpty();
        assertThat(redisKeys(key)).isEmpty();
        var values = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("checkout:*").build())
                .filter(redisKey -> redisKey.contains(user) || redisKey.contains("session:") || redisKey.contains("payload:")
                        || redisKey.contains("idem:") || redisKey.contains("result:"))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(values).isNotEmpty();
        for (String redisKey : values) {
            String dump = dump(redisKey);
            assertThat(dump).doesNotContain(token, "Nguyen Van A", "12 Le Loi", "+84901234567", "secret-from-post",
                    "pk_test_1", "_secret_");
        }
    }

    private String dump(String redisKey) {
        String type = redis.type(redisKey).map(Object::toString).block(Duration.ofSeconds(5));
        if ("HASH".equalsIgnoreCase(type)) {
            return redis.opsForHash().entries(redisKey).collectList().block(Duration.ofSeconds(5)).toString();
        }
        String value = redis.opsForValue().get(redisKey).block(Duration.ofSeconds(5));
        return value == null ? "" : value;
    }
}
