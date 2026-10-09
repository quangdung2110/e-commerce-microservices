package com.ecommerce.checkout;

import com.ecommerce.checkout.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that matches no operation never comes through the Gateway (it forwards four routes), but a
 * caller that reaches the service directly still gets what the contract promises for every error: a
 * {@code CheckoutProblem}, the HTTP status of the framework, and {@code Cache-Control: private, no-store}.
 */
class UnroutedRequestTest extends AbstractCheckoutTest {

    private void assertProblem(WebTestClient.ResponseSpec response, int status) throws IOException {
        EntityExchangeResult<byte[]> result = response.expectBody().returnResult();
        assertThat(result.getStatus().value()).isEqualTo(status);
        assertThat(String.valueOf(result.getResponseHeaders().getContentType())).as("content type")
                .startsWith("application/problem+json");
        assertThat(result.getResponseHeaders().getCacheControl()).isEqualTo("private, no-store");
        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(ContractSchemas.validate("CheckoutProblem", body)).isEmpty();
    }

    @Test
    @DisplayName("An unknown route is a 404 problem")
    void unknownRoute() throws IOException {
        assertProblem(client.get().uri("/api/v1/checkout/nope").header("X-User-Id", newUser()).exchange(), 404);
    }

    @Test
    @DisplayName("A known path with another method is a 405 problem")
    void wrongMethod() throws IOException {
        assertProblem(client.get().uri("/api/v1/checkout/submit").header("X-User-Id", newUser()).exchange(), 405);
    }

    @Test
    @DisplayName("A body that is not JSON is a 400 problem whatever its media type")
    void bodyThatIsNotJson() throws IOException {
        assertProblem(client.post().uri("/api/v1/checkout/preview").header("X-User-Id", newUser())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.TEXT_PLAIN).bodyValue("x").exchange(), 400);
    }
}
