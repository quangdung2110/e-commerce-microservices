package com.ecommerce.pdp.client;

import com.ecommerce.pdp.Fixtures;
import com.ecommerce.pdp.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tolerant reader và các ràng buộc của {@code BuyerProductDetail} (ADR-005). */
class ProductPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();

    @Test
    @DisplayName("Payload đúng contract hợp lệ, field không biết bị bỏ qua")
    void validPayloadPasses() {
        assertThat(read(Fixtures.product()).validated().skus()).hasSize(3);
    }

    @Test
    @DisplayName("Số 42 và 42.0 là cùng một option")
    void numericOptionsCompareByValue() {
        ProductPayload payload = read(mutate(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42,
                "W-BLACK-42", Map.of("size", 42.0, "color", "black"), null))));

        assertThat(payload.validated()).isNotNull();
    }

    @Test
    @DisplayName("Chuỗi \"42\" không khớp option số 42")
    void stringDoesNotMatchNumericOption() {
        assertViolation(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42,
                "W-BLACK-42", Map.of("size", "42", "color", "black"), null)));
    }

    @Test
    @DisplayName("Mỗi vi phạm contract đều bị bắt")
    void contractViolationsAreRejected() {
        assertViolation(product -> product.remove("product_id"));
        assertViolation(product -> product.put("catalog_revision", 0));
        assertViolation(product -> product.put("skus", List.of()));
        assertViolation(product -> skus(product).add(skus(product).get(0)));
        assertViolation(product -> media(product).add(Fixtures.media("m-hero", null, "IMAGE", "x.jpg", 0)));
        assertViolation(product -> media(product).add(Fixtures.media("m-x", "sku-gone", "IMAGE", "x.jpg", 0)));
        assertViolation(product -> media(product).add(Fixtures.media("m-y", null, "AUDIO", "x.mp3", 0)));
        assertViolation(product -> skus(product).add(Fixtures.sku("sku-z", "Z", Map.of("finish", "matte"), null)));
        assertViolation(product -> attributes(product).add(Fixtures.attribute("x", "X", "TEXT", Map.of(), null)));
        assertViolation(product -> attributes(product).add(Fixtures.attribute("y", "Y", "COLOR", "red", null)));
    }

    private void assertViolation(Consumer<Map<String, Object>> mutation) {
        ProductPayload payload = read(mutate(mutation));
        assertThatThrownBy(payload::validated).isInstanceOf(ContractViolationException.class);
    }

    private Map<String, Object> mutate(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> product = Fixtures.product();
        mutation.accept(product);
        return product;
    }

    private ProductPayload read(Map<String, Object> product) {
        try {
            return mapper.readValue(Fixtures.json(product), ProductPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> skus(Map<String, Object> product) {
        return (List<Map<String, Object>>) product.get("skus");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> media(Map<String, Object> product) {
        return (List<Map<String, Object>>) product.get("media");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributes(Map<String, Object> product) {
        return (List<Map<String, Object>>) product.get("attributes");
    }
}
