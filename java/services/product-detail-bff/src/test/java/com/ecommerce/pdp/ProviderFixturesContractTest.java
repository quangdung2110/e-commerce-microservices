package com.ecommerce.pdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture WireMock phải đúng contract của provider thật, nếu không test của BFF chỉ chứng
 * minh BFF hiểu một Product/Inventory/Review tưởng tượng.
 */
class ProviderFixturesContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Fixture Product khớp BuyerProductDetail (bỏ field cố ý thêm cho tolerant reader)")
    void productFixtureMatchesProductContract() {
        Map<String, Object> product = Fixtures.product();
        product.remove("future_field");

        assertThat(ContractSchemas.validate(ContractSchemas.PRODUCT, "BuyerProductDetail", tree(product))).isEmpty();
    }

    @Test
    @DisplayName("Fixture Inventory khớp BatchSkuAvailabilityResponse")
    void inventoryFixtureMatchesInventoryContract() {
        Map<String, Object> inventory = Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_WHITE_42, "UNKNOWN")));

        assertThat(ContractSchemas.validate(ContractSchemas.INVENTORY, "BatchSkuAvailabilityResponse", tree(inventory)))
                .isEmpty();
    }

    @Test
    @DisplayName("Fixture Review khớp ProductReviewPdpSummary, kể cả khi chưa có review")
    void reviewFixturesMatchReviewContract() {
        assertThat(ContractSchemas.validate(ContractSchemas.REVIEW, "ProductReviewPdpSummary", tree(Fixtures.review())))
                .isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.REVIEW, "ProductReviewPdpSummary",
                tree(Fixtures.emptyReview()))).isEmpty();
    }

    private JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }
}
