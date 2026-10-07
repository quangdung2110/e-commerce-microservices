package com.ecommerce.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code CHECKOUT_VOUCHER_OPTIONS_ENABLED=false}: the tray answers 503 and never calls Promotion. */
@TestPropertySource(properties = "checkout.voucher-options-enabled=false")
class VoucherOptionsDisabledTest extends AbstractCheckoutTest {

    @Test
    @DisplayName("A disabled tray is 503 VOUCHER_OPTIONS_UNAVAILABLE and Promotion is not called")
    void disabledFlagDoesNotCallPromotion() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();

        JsonNode problem = bodyOf(voucherOptions(user, token).expectStatus().isEqualTo(503));

        assertThat(problem.path("code").asText()).isEqualTo("VOUCHER_OPTIONS_UNAVAILABLE");
        assertThat(posted(PROMOTION_OPTIONS_PATH)).isEmpty();
        submit(user, token).expectStatus().isCreated();
    }
}
