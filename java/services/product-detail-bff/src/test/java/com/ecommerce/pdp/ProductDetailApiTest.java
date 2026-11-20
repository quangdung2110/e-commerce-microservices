package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-PDP-001 qua HTTP thật: contract public, degrade theo từng dependency, header gửi đi
 * và phân loại lỗi của ADR-004/ADR-007.
 */
class ProductDetailApiTest extends AbstractPdpTest {

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("Response đầy đủ khớp contract, giữ thứ tự Product và dùng cache policy của storefront")
    void fullResponseMatchesContract() {
        stubHealthyUpstreams();

        EntityExchangeResult<byte[]> result = getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", FULL_CACHE_CONTROL)
                .expectBody().returnResult();
        JsonNode body = body(result);

        assertThat(ContractSchemas.validate("ProductDetailResponse", body)).isEmpty();
        assertThat(body.path("category").path("path").findValuesAsText("slug"))
                .containsExactly("electronics", "watches");
        assertThat(body.path("attributes").findValuesAsText("code")).containsExactly("material", "brand");
        assertThat(body.path("variant_axes").findValuesAsText("code")).containsExactly("size", "color");
        assertThat(body.path("skus").findValuesAsText("availability_status"))
                .containsExactly("IN_STOCK", "OUT_OF_STOCK", "IN_STOCK");
        assertThat(body.path("review_section").path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(body.path("review_section").path("summary").path("total_reviews").asLong()).isEqualTo(12);
    }

    @Test
    @DisplayName("URL media dựng từ CDN và storage key, SKU không ảnh nhận placeholder, không lộ storage key")
    void mediaUrlsAreBuiltAndStorageKeysNeverLeak() {
        stubHealthyUpstreams();

        EntityExchangeResult<byte[]> result = getPdp("smart-watch").expectStatus().isOk().expectBody().returnResult();
        JsonNode body = body(result);

        assertThat(body.path("media").get(0).path("url").asText())
                .isEqualTo(CDN + "/products/watch/hero%20shot.jpg");
        assertThat(body.path("media").get(0).path("sku_id").isNull()).isTrue();
        assertThat(body.path("skus").get(0).path("thumbnail_url").asText())
                .isEqualTo(CDN + "/skus/black-42/front.jpg");
        assertThat(body.path("skus").get(1).path("thumbnail_url").asText()).isEqualTo(PLACEHOLDER);
        String raw = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("storage_key").doesNotContain("future_field");
    }

    @Test
    @DisplayName("Đúng một Inventory batch cho mọi SKU, header và token gửi đúng cho từng dependency")
    void dependenciesReceiveOneBatchAndTheRightHeaders() {
        stubHealthyUpstreams();

        client.get().uri("/api/v1/storefront/products/smart-watch")
                .header("X-Request-Id", "req-12345678")
                .exchange()
                .expectStatus().isOk();

        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH))
                .withHeader("X-Service-Identity", equalTo("product-detail-bff"))
                .withHeader("X-Request-Id", equalTo("req-12345678"))
                .withHeader("X-Request-Timeout-Ms", matching("[0-9]{1,4}")));
        UPSTREAMS.verify(1, postRequestedFor(urlEqualTo(INVENTORY_PATH))
                .withHeader("X-Service-Identity", equalTo("product-detail-bff"))
                .withHeader("X-Correlation-ID", equalTo("req-12345678"))
                .withHeader("X-Request-Id", equalTo("req-12345678"))
                .withHeader("X-Request-Timeout-Ms", matching("[0-9]{1,4}"))
                .withRequestBody(equalToJson(Fixtures.json(Map.of("sku_ids", List.of(
                        Fixtures.SKU_BLACK_42, Fixtures.SKU_WHITE_42, Fixtures.SKU_BLACK_46))))));
        UPSTREAMS.verify(1, getRequestedFor(urlPathEqualTo(REVIEW_PATH))
                .withQueryParam("preview_limit", equalTo("3"))
                .withHeader("X-Service-Identity", equalTo("product-detail-bff"))
                .withoutHeader("Authorization")
                .withHeader("X-Request-Id", equalTo("req-12345678"))
                .withHeader("X-Request-Timeout-Ms", matching("[0-9]{1,4}")));
    }

    @Test
    @DisplayName("X-Request-Id sai bị thay bằng trace ID của traceparent đầu vào, traceparent được truyền tiếp")
    void malformedRequestIdIsReplacedByTraceId() {
        stubHealthyUpstreams();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        client.get().uri("/api/v1/storefront/products/smart-watch")
                .header("X-Request-Id", "abc")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        UPSTREAMS.verify(1, postRequestedFor(urlEqualTo(INVENTORY_PATH))
                .withHeader("X-Correlation-ID", equalTo(traceId))
                .withHeader("traceparent", matching("00-" + traceId + "-[0-9a-f]{16}-0[01]")));
        UPSTREAMS.verify(1, getRequestedFor(urlPathEqualTo(REVIEW_PATH))
                .withHeader("X-Request-Id", equalTo(traceId)));
    }

    // ------------------------------------------------------------ Inventory

    @Test
    @DisplayName("Item thiếu thành UNKNOWN, item thừa bị bỏ, cả hai ghi vi phạm mức item")
    void missingAndExtraInventoryItemsDegradeOnlyThoseSkus() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability("ghost-sku", "IN_STOCK"))));
        stubReview(Fixtures.review());
        double missingBefore = counter("pdp.inventory.item.violations", "kind", "missing");
        double extraBefore = counter("pdp.inventory.item.violations", "kind", "extra");

        JsonNode body = body(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().returnResult());

        assertThat(body.path("skus").findValuesAsText("availability_status"))
                .containsExactly("IN_STOCK", "UNKNOWN", "UNKNOWN");
        assertThat(body.path("skus").findValuesAsText("sku_id")).doesNotContain("ghost-sku");
        assertThat(counter("pdp.inventory.item.violations", "kind", "missing") - missingBefore).isEqualTo(2);
        assertThat(counter("pdp.inventory.item.violations", "kind", "extra") - extraBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("UNKNOWN do Inventory trả được giữ nguyên, không có thời điểm quan sát")
    void inventoryUnknownIsKept() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "UNKNOWN"),
                Fixtures.availability(Fixtures.SKU_WHITE_42, "OUT_OF_STOCK"),
                Fixtures.availability(Fixtures.SKU_BLACK_46, "IN_STOCK"))));
        stubReview(Fixtures.review());

        JsonNode sku = body(getPdp("smart-watch").expectStatus().isOk().expectBody().returnResult())
                .path("skus").get(0);

        assertThat(sku.path("availability_status").asText()).isEqualTo("UNKNOWN");
        assertThat(sku.path("availability_observed_at").isNull()).isTrue();
    }

    @Test
    @DisplayName("Inventory 5xx làm mọi SKU UNKNOWN, catalog vẫn 200 no-store và lỗi tính vào circuit")
    void inventoryServerErrorDegradesEverySku() {
        stubProduct(Fixtures.product());
        stubInventory(status(503));
        stubReview(Fixtures.review());

        JsonNode body = body(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().returnResult());

        assertThat(body.path("skus").findValuesAsText("availability_status")).containsOnly("UNKNOWN");
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Inventory 403 làm mọi SKU UNKNOWN nhưng không tính vào circuit")
    void inventoryForbiddenIsNotCountedByTheBreaker() {
        stubProduct(Fixtures.product());
        stubInventory(status(403));
        stubReview(Fixtures.review());
        long before = calls(Dependency.INVENTORY, "4xx");

        JsonNode body = body(getPdp("smart-watch").expectStatus().isOk().expectBody().returnResult());

        assertThat(body.path("skus").findValuesAsText("availability_status")).containsOnly("UNKNOWN");
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(calls(Dependency.INVENTORY, "4xx") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("Inventory trùng sku_id là sai contract: bỏ cả batch")
    void duplicateInventoryItemsVoidTheBatch() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_BLACK_42, "OUT_OF_STOCK"))));
        stubReview(Fixtures.review());
        long before = calls(Dependency.INVENTORY, "contract_violation");

        JsonNode body = body(getPdp("smart-watch").expectStatus().isOk().expectBody().returnResult());

        assertThat(body.path("skus").findValuesAsText("availability_status")).containsOnly("UNKNOWN");
        assertThat(calls(Dependency.INVENTORY, "contract_violation") - before).isEqualTo(1);
    }

    // ------------------------------------------------------------ Review

    @Test
    @DisplayName("Review chưa có dữ liệu là AVAILABLE với aggregate 0, không phải UNAVAILABLE")
    void emptyReviewIsAvailable() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_WHITE_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_BLACK_46, "IN_STOCK"))));
        stubReview(Fixtures.emptyReview());

        JsonNode section = body(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", FULL_CACHE_CONTROL)
                .expectBody().returnResult()).path("review_section");

        assertThat(section.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(section.path("summary").path("total_reviews").asLong()).isZero();
        assertThat(section.path("preview").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Review chậm hơn timeout là UNAVAILABLE, không giả aggregate 0")
    void slowReviewIsUnavailable() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
        stubReview(json(Fixtures.review()).withFixedDelay(700));
        long before = calls(Dependency.REVIEW, "timeout");

        JsonNode section = body(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().returnResult()).path("review_section");

        assertThat(section.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(section.path("summary").isNull()).isTrue();
        assertThat(section.path("preview").isEmpty()).isTrue();
        assertThat(calls(Dependency.REVIEW, "timeout") - before).isEqualTo(1);
    }

    // ------------------------------------------------------------ Product

    @Test
    @DisplayName("Product 404 trả 404 problem no-store và không gọi Inventory/Review")
    void productNotFoundIsNotFound() {
        stubProduct(status(404));

        JsonNode problem = body(getPdp("smart-watch").expectStatus().isNotFound()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentType("application/problem+json")
                .expectBody().returnResult());

        assertThat(ContractSchemas.validate("Problem", problem)).isEmpty();
        assertThat(problem.path("code").asText()).isEqualTo("PRODUCT_DETAIL_NOT_FOUND");
        assertThat(problem.path("retryable").asBoolean()).isFalse();
        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(0, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Slug sai pattern hoặc dài hơn 180 trả 404 mà không gọi Product")
    void malformedSlugNeverReachesProduct() {
        for (String slug : List.of("Smart-Watch", "smart_watch", "smart--watch", "a".repeat(181))) {
            getPdp(slug).expectStatus().isNotFound()
                    .expectHeader().valueEquals("Cache-Control", "no-store");
        }

        UPSTREAMS.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Product 5xx trả 503 problem có trace_id và Retry-After mặc định khi circuit chưa mở")
    void productServerErrorIsUnavailable() {
        stubProduct(status(500));

        EntityExchangeResult<byte[]> result = getPdp("smart-watch")
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Retry-After", "2")
                .expectBody().returnResult();
        JsonNode problem = body(result);

        assertThat(ContractSchemas.validate("Problem", problem)).isEmpty();
        assertThat(problem.path("code").asText()).isEqualTo("PRODUCT_DETAIL_UNAVAILABLE");
        assertThat(problem.path("retryable").asBoolean()).isTrue();
        assertThat(problem.path("trace_id").asText()).matches("[0-9a-f]{32}");
        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
    }

    @Test
    @DisplayName("Product 401 trả 503 nhưng không tính vào circuit")
    void productUnauthorizedIsNotCountedByTheBreaker() {
        stubProduct(status(401));

        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Product vi phạm ràng buộc tham chiếu là sai contract: 503 và tính vào circuit")
    void productReferentialViolationsAreContractViolations() {
        Map<String, Object> orphanMedia = Fixtures.product();
        mutableList(orphanMedia, "media").add(Fixtures.media("m-orphan", "sku-gone", "IMAGE", "x.jpg", 0));
        Map<String, Object> unknownOption = Fixtures.product();
        mutableList(unknownOption, "skus").add(Fixtures.sku("sku-red", "W-RED-42",
                Map.of("size", 42, "color", "red"), null));
        Map<String, Object> missingCategory = Fixtures.product();
        missingCategory.remove("category");

        for (Map<String, Object> broken : List.of(orphanMedia, unknownOption, missingCategory)) {
            stubProduct(broken);
            getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        stubProduct(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{not json"));
        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
    }

    @Test
    @DisplayName("Circuit Product mở trả 503 kèm Retry-After và không gọi Product")
    void openProductCircuitFailsFastWithRetryAfter() {
        stubHealthyUpstreams();
        guard.breaker(Dependency.PRODUCT).transitionToOpenState();

        EntityExchangeResult<byte[]> result = getPdp("smart-watch")
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().returnResult();

        int retryAfter = Integer.parseInt(result.getResponseHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isBetween(1, 10);
        UPSTREAMS.verify(0, anyRequestedFor(anyUrl()));
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableList(Map<String, Object> product, String key) {
        List<Map<String, Object>> copy = new ArrayList<>((List<Map<String, Object>>) product.get(key));
        product.put(key, copy);
        return copy;
    }

    private JsonNode body(EntityExchangeResult<byte[]> result) {
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
