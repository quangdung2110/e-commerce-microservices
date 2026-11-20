package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bảng "Health và degradation" của operations.md cho từng dependency: status lạ, timeout,
 * không retry ở ứng dụng và batch tối đa 500 SKU (ADR-004 §3-4, development.md).
 */
class DependencyStatusTest extends AbstractPdpTest {

    // ----------------------------------------------- status ngoài contract (ADR-004 §3)

    @Test
    @DisplayName("Inventory trả status lạ (429, 404, 302) là ngoài contract: mọi SKU UNKNOWN và tính vào circuit")
    void unexpectedInventoryStatusIsACountedContractViolation() {
        for (int status : List.of(429, 404, 302)) {
            stubProduct(Fixtures.product());
            stubInventory(status(status));
            stubReview(Fixtures.review());
            long before = calls(Dependency.INVENTORY, "contract_violation");

            JsonNode body = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", "no-store"));

            assertThat(availabilityStatuses(body)).as("status %s", status).containsOnly("UNKNOWN");
            assertThat(calls(Dependency.INVENTORY, "contract_violation") - before).as("status %s", status).isEqualTo(1);
        }
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("Review trả status lạ (429, 404) là UNAVAILABLE và tính vào circuit; 400/401/403 thì không")
    void unexpectedReviewStatusIsACountedContractViolation() {
        for (int status : List.of(429, 404)) {
            stubProduct(Fixtures.product());
            stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
            stubReview(status(status));

            getPdp("smart-watch").expectStatus().isOk()
                    .expectBody().jsonPath("$.review_section.status").isEqualTo("UNAVAILABLE");
        }
        assertThat(guard.breaker(Dependency.REVIEW).getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        guard.breaker(Dependency.REVIEW).reset();
        for (int status : List.of(400, 401, 403)) {
            stubReview(status(status));
            getPdp("smart-watch").expectStatus().isOk()
                    .expectBody().jsonPath("$.review_section.status").isEqualTo("UNAVAILABLE");
        }
        assertThat(guard.breaker(Dependency.REVIEW).getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Product trả status lạ (429, 302) là 503 và tính vào circuit; 400/403 thì không")
    void unexpectedProductStatusIsACountedContractViolation() {
        for (int status : List.of(429, 302)) {
            stubProduct(status(status));
            getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        guard.breaker(Dependency.PRODUCT).reset();
        for (int status : List.of(400, 403)) {
            stubProduct(status(status));
            getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Review 400/401/403 là UNAVAILABLE kèm outcome 4xx, không tính vào circuit")
    void reviewConfigurationErrorsAreRecordedAs4xx() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
        stubReview(status(401));
        long before = calls(Dependency.REVIEW, "4xx");

        getPdp("smart-watch").expectStatus().isOk()
                .expectBody().jsonPath("$.review_section.status").isEqualTo("UNAVAILABLE");

        assertThat(calls(Dependency.REVIEW, "4xx") - before).isEqualTo(1);
    }

    // ----------------------------------------------- không retry ở ứng dụng

    @Test
    @DisplayName("Không retry ở ứng dụng: lỗi 5xx của từng dependency chỉ phát đúng một request")
    void serverErrorsAreNeverRetried() {
        stubProduct(status(500));
        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH)));

        stubProduct(Fixtures.product());
        stubInventory(status(503));
        stubReview(status(500));
        getPdp("smart-watch").expectStatus().isOk();
        UPSTREAMS.verify(1, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(1, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
    }

    // ----------------------------------------------- timeout theo từng dependency

    @Test
    @DisplayName("Product chậm hơn timeout là 503 outcome timeout và không gọi Inventory/Review")
    void slowProductIsUnavailable() {
        stubProduct(json(Fixtures.product()).withFixedDelay(900));
        long before = calls(Dependency.PRODUCT, "timeout");

        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Cache-Control", "no-store");

        assertThat(calls(Dependency.PRODUCT, "timeout") - before).isEqualTo(1);
        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(0, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
    }

    @Test
    @DisplayName("Inventory chậm hơn timeout làm mọi SKU UNKNOWN, catalog và Review vẫn có")
    void slowInventoryDegradesEverySku() {
        stubProduct(Fixtures.product());
        stubInventory(json(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))))
                .withFixedDelay(700));
        stubReview(Fixtures.review());
        long before = calls(Dependency.INVENTORY, "timeout");
        double unknownBefore = counter("pdp.sku.unknown", "source", "call_failed");

        JsonNode body = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store"));

        assertThat(availabilityStatuses(body)).containsOnly("UNKNOWN");
        assertThat(body.path("review_section").path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(calls(Dependency.INVENTORY, "timeout") - before).isEqualTo(1);
        assertThat(counter("pdp.sku.unknown", "source", "call_failed") - unknownBefore).isEqualTo(3);
    }

    // ----------------------------------------------- batch tối đa 500 SKU

    @Test
    @DisplayName("Product 500 SKU: đúng một Inventory batch mang đủ 500 sku_id")
    void fiveHundredSkusAreOneBatch() {
        Map<String, Object> product = Fixtures.product();
        List<Map<String, Object>> skus = new ArrayList<>();
        List<Map<String, Object>> availability = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String id = String.format("sku-%03d", i);
            ids.add(id);
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("size", 42);
            variant.put("color", "black");
            skus.add(Fixtures.sku(id, "W-" + id, variant, null));
            availability.add(Fixtures.availability(id, i % 2 == 0 ? "IN_STOCK" : "OUT_OF_STOCK"));
        }
        product.put("skus", skus);
        product.put("media", new ArrayList<>());
        stubProduct(product);
        stubInventory(Fixtures.inventory(availability));
        stubReview(Fixtures.review());

        JsonNode body = bodyOf(getPdp("smart-watch").expectStatus().isOk());

        assertThat(body.path("skus")).hasSize(500);
        assertThat(availabilityStatuses(body).stream().distinct()).containsExactlyInAnyOrder("IN_STOCK", "OUT_OF_STOCK");
        UPSTREAMS.verify(1, postRequestedFor(urlEqualTo(INVENTORY_PATH))
                .withRequestBody(equalToJson(Fixtures.json(Map.of("sku_ids", ids)))));
    }
}
