package com.ecommerce.checkout;

import com.ecommerce.checkout.domain.SessionState;
import com.ecommerce.checkout.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Submit deadline short enough that revalidation leaves less than the 2 000 ms Order needs: Order is
 * not called, the token becomes RETRYABLE and the client polls (architecture.md, section 5).
 */
@TestPropertySource(properties = "checkout.deadlines.submit=2300ms")
class ShortDeadlineSubmitTest extends AbstractCheckoutTest {

    @Autowired
    private SessionStore store;

    @Test
    @DisplayName("With less than 2 000 ms left after revalidation Order is not called: 202 RESULT_UNKNOWN and RETRYABLE")
    void orderIsNotCalledWithLessThanTwoSecondsLeft() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        PRODUCT.delay(450); // revalidation now consumes most of the 2 300 ms deadline

        JsonNode body = bodyOf(submit(user, token).expectStatus().isAccepted().expectHeader().exists("Retry-After"));

        assertThat(body.path("state").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(orderPosts()).isZero();
        var control = store.findControl(token).block().orElseThrow();
        assertThat(control.state()).isEqualTo(SessionState.RETRYABLE);
        assertThat(control.revalidatedAtMs()).as("revalidation finished").isNotNull();
        assertThat(control.lastOrderCallStartedAtMs()).as("no Order call was started").isNull();
    }
}
