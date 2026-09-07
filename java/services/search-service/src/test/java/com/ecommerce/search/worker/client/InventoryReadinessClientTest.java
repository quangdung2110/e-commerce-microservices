package com.ecommerce.search.worker.client;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.config.WebConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The readiness handshake against the Inventory export contract.
 *
 * <p>Two of these cases are the ones that decide whether the catalog consumer stalls or
 * moves on: an unavailable Inventory must leave the offset uncommitted, while a {@code 409}
 * must not be retried at all ({@code WF-SEARCH-012}).
 */
class InventoryReadinessClientTest {

    private static final String BASE = "http://inventory-service:8080";

    private MockRestServiceServer inventory;
    private InventoryReadinessClient client;

    @BeforeEach
    void setUp() {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", BASE, "http://review-service:8080",
                true, Duration.ofSeconds(30)));

        WebConfig config = new WebConfig(properties);
        RestClient.Builder builder = config.restClientBuilder(config.objectMapper());
        inventory = MockRestServiceServer.bindTo(builder).build();
        client = new InventoryReadinessClient(builder, properties, new SearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void registersTheAppliedCatalogRevisionForTheSku() {
        inventory.expect(requestTo(BASE + "/exports/inventory/v1/search-projection-readiness/sku-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.catalog_revision").value(7))
                .andRespond(withSuccess());

        assertThat(client.register("sku-1", 7L)).isEqualTo(InventoryReadinessClient.Outcome.OK);
        inventory.verify();
    }

    @Test
    void treatsAnUnavailableInventoryAsAReasonToLeaveTheOffsetUncommitted() {
        inventory.expect(requestTo(BASE + "/exports/inventory/v1/search-projection-readiness/sku-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.register("sku-1", 7L)).isEqualTo(InventoryReadinessClient.Outcome.FAILED);
    }

    @Test
    void revokesWithTheTombstoneCatalogRevisionInTheQuery() {
        inventory.expect(requestTo(
                        BASE + "/exports/inventory/v1/search-projection-readiness/sku-1?catalog_revision=9"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.revoke("sku-1", 9L)).isEqualTo(InventoryReadinessClient.Outcome.OK);
        inventory.verify();
    }

    @Test
    void reportsAConflictOnRevokeAsDivergenceRatherThanAFailureToRetry() {
        // Search's is_deleted guard rules out a document reviving, so inventory holding a
        // newer registration means the two have drifted apart (docs commit 47ef994).
        inventory.expect(requestTo(
                        BASE + "/exports/inventory/v1/search-projection-readiness/sku-1?catalog_revision=9"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(client.revoke("sku-1", 9L)).isEqualTo(InventoryReadinessClient.Outcome.CONFLICT);
    }

    @Test
    void treatsAFailedRevokeAsRetryable() {
        inventory.expect(requestTo(
                        BASE + "/exports/inventory/v1/search-projection-readiness/sku-1?catalog_revision=9"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.revoke("sku-1", 9L)).isEqualTo(InventoryReadinessClient.Outcome.FAILED);
    }
}
