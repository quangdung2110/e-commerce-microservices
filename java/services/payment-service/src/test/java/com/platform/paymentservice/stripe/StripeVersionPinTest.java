package com.platform.paymentservice.stripe;

import com.stripe.Stripe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeVersionPinTest {
    @Test
    void sdk_and_api_versions_are_pinned_together() {
        assertThat(Stripe.VERSION).isEqualTo("33.4.2");
        assertThat(Stripe.API_VERSION).isEqualTo("2026-08-26.dahlia");
    }
}
