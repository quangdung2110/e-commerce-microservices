package com.ecommerce.checkout;

import com.ecommerce.checkout.store.SessionStore;
import com.ecommerce.checkout.support.ContractSchemas;
import com.ecommerce.checkout.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** WF-08 voucher tray (ADR-017). */
class VoucherOptionsApiTest extends AbstractCheckoutTest {

    @Autowired
    private SessionStore store;

    @Test
    @DisplayName("Options map required_action, keep Promotion's order, mark the applied voucher and drop what is unknown")
    void mapsOptions() {
        String user = newUser();
        String token = previewOk(user, null, Fixtures.PROMOTION_ID).path("checkout_token").asText();
        stubPromotionOptions(Fixtures.voucherOptions(List.of(
                Fixtures.voucherOption("promo-1", "WALLET", "PUBLIC_SAVE", true, "ELIGIBLE", 25_000, 3,
                        Fixtures.percentage(1000, null)),
                Fixtures.voucherOption("promo-2", "PUBLIC", "PUBLIC_SAVE", true, "ELIGIBLE", 20_000, 5,
                        Fixtures.fixed(20_000)),
                Fixtures.voucherOption("promo-3", "PUBLIC", "LIMITED_CLAIM", false, "SOMETHING_NEW", 0, 7,
                        Fixtures.percentage(500, 30_000L)),
                Fixtures.voucherOption("promo-4", "UNKNOWN", "PUBLIC_SAVE", true, "ELIGIBLE", 1, 1,
                        Fixtures.fixed(1)),
                Fixtures.voucherOption("promo-5", "PUBLIC", "SOMETHING_ELSE", true, "ELIGIBLE", 1, 1,
                        Fixtures.fixed(1)),
                Fixtures.voucherOption("promo-6", "PUBLIC", "PUBLIC_SAVE", true, "ELIGIBLE", 1, 1,
                        Fixtures.map("type", "TIERED")))));

        var response = voucherOptions(user, token).expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "private, no-store");
        JsonNode body = bodyOf(response);

        assertThat(ContractSchemas.validate("VoucherOptions", body)).isEmpty();
        assertThat(body.path("checkout_token").asText()).isEqualTo(token);
        assertThat(body.path("applied_promotion_id").asText()).isEqualTo("promo-1");
        JsonNode items = body.path("items");
        assertThat(items).hasSize(3);
        assertThat(items.findValuesAsText("promotion_id")).containsExactly("promo-1", "promo-2", "promo-3");
        assertThat(items.get(0).path("required_action").asText()).isEqualTo("NONE");
        assertThat(items.get(0).path("source").asText()).isEqualTo("WALLET");
        assertThat(items.get(0).path("is_applied").asBoolean()).isTrue();
        assertThat(items.get(1).path("required_action").asText()).isEqualTo("SAVE");
        assertThat(items.get(1).path("is_applied").asBoolean()).isFalse();
        assertThat(items.get(1).path("discount_rule").path("type").asText()).isEqualTo("FIXED_VND");
        assertThat(items.get(1).path("discount_rule").path("value").path("amount_minor").asLong()).isEqualTo(20_000);
        assertThat(items.get(2).path("required_action").asText()).isEqualTo("CLAIM");
        assertThat(items.get(2).path("expected_campaign_revision").asLong()).isEqualTo(7);
        assertThat(items.get(2).path("reason").asText()).isEqualTo("UNKNOWN");
        assertThat(items.get(2).path("discount_rule").path("max_discount").path("amount_minor").asLong())
                .isEqualTo(30_000);
        assertThat(items.get(0).path("discount_rule").path("max_discount").isNull()).isTrue();
        assertThat(items.get(0).path("estimated_discount").path("amount_minor").asLong()).isEqualTo(25_000);
        assertThat(items.get(0).path("estimated_final_amount").path("amount_minor").asLong()).isEqualTo(225_000);
        assertThat(counter("checkout.voucher.options.dropped", "reason", "source")).isPositive();
        assertThat(counter("checkout.voucher.options.dropped", "reason", "distribution_mode")).isPositive();
        assertThat(counter("checkout.voucher.options.dropped", "reason", "discount_type")).isPositive();
    }

    @Test
    @DisplayName("The Promotion context comes from the token's snapshot: Cart and Product are not called again")
    void contextFromSnapshot() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        int cartCalls = requests(getRequestedFor(urlPathMatching(CART_PATH)));
        // Change the world after the quotation: the tray must still describe the quotation.
        stubCart(Fixtures.cart(Fixtures.cartLine(Fixtures.PRODUCT_1, "sku-1", 9)));
        PRODUCT.reset();
        stubPromotionOptions(Fixtures.voucherOptions(List.of()));

        voucherOptions(user, token).expectStatus().isOk();

        assertThat(requests(getRequestedFor(urlPathMatching(CART_PATH)))).isEqualTo(cartCalls);
        assertThat(PRODUCT.calls()).as("Product was not called again").isZero();
        JsonNode request = requestBody(posted(PROMOTION_OPTIONS_PATH).get(0));
        assertThat(request.path("user_id").asText()).isEqualTo(user);
        assertThat(request.has("user_tier")).isFalse();
        assertThat(request.path("include_public").asBoolean()).isTrue();
        assertThat(request.path("include_ineligible").asBoolean()).isTrue();
        assertThat(request.path("limit").asInt()).isEqualTo(50);
        JsonNode context = request.path("context");
        assertThat(context.path("subtotal").path("amount_minor").asLong()).isEqualTo(250_000);
        assertThat(context.path("checkout_channel").asText()).isEqualTo("CHECKOUT");
        assertThat(context.path("payment_method").asText()).isEqualTo("STRIPE");
        assertThat(context.path("items")).hasSize(2);
        assertThat(context.path("items").get(0).path("sku_id").asText()).isEqualTo("sku-1");
        assertThat(context.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(context.path("items").get(0).path("category_ids").toString())
                .isEqualTo("[\"cat-root\",\"cat-shirts\"]");
    }

    @Test
    @DisplayName("Only a PREVIEWED, unexpired quotation has a tray: 409 once submitted, 410 when superseded or expired")
    void tokenStates() {
        String user = newUser();
        stubPromotionOptions(Fixtures.voucherOptions(List.of()));

        String superseded = previewOk(user).path("checkout_token").asText();
        String current = previewOk(user).path("checkout_token").asText();
        JsonNode gone = bodyOf(voucherOptions(user, superseded).expectStatus().isEqualTo(410));
        assertThat(gone.path("code").asText()).isEqualTo("CHECKOUT_SUPERSEDED");

        voucherOptions(user, current).expectStatus().isOk();
        submit(user, current).expectStatus().isCreated();
        JsonNode submitted = bodyOf(voucherOptions(user, current).expectStatus().isEqualTo(409));
        assertThat(submitted.path("code").asText()).isEqualTo("CHECKOUT_ALREADY_SUBMITTED");
        assertThat(submitted.path("action").asText()).isEqualTo("VIEW_SUBMISSION");
        assertThat(submitted.path("status_url").asText()).isEqualTo("/api/v1/checkout/submissions/" + current);
        assertThat(ContractSchemas.validate("CheckoutProblem", submitted)).isEmpty();

        String expiring = previewOk(user).path("checkout_token").asText();
        clock.advance(Duration.ofMinutes(21));
        assertThat(bodyOf(voucherOptions(user, expiring).expectStatus().isEqualTo(410)).path("code").asText())
                .isEqualTo("CHECKOUT_EXPIRED");
        voucherOptions(user, java.util.UUID.randomUUID().toString()).expectStatus().isNotFound();
    }

    @Test
    @DisplayName("A Promotion failure is 503 VOUCHER_OPTIONS_UNAVAILABLE and Preview and Submit still work")
    void promotionFailureIsIsolated() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        UPSTREAMS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(PROMOTION_OPTIONS_PATH))
                .willReturn(problem(503, "UNAVAILABLE")));

        var response = voucherOptions(user, token).expectStatus().isEqualTo(503).expectHeader().exists("Retry-After");
        JsonNode problem = bodyOf(response);
        assertThat(problem.path("code").asText()).isEqualTo("VOUCHER_OPTIONS_UNAVAILABLE");
        assertThat(problem.path("retryable").asBoolean()).isTrue();
        assertThat(problem.path("action").asText()).isEqualTo("RETRY");
        assertThat(ContractSchemas.validate("CheckoutProblem", problem)).isEmpty();

        submit(user, token).expectStatus().isCreated();
        previewOk(user);
    }
}
