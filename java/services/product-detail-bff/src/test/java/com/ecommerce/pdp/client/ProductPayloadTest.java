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

    @Test
    @DisplayName("Storage key có segment rỗng, \".\" hoặc \"..\" thoát khỏi thư mục CDN nên là sai contract")
    void storageKeysThatEscapeTheCdnDirectoryAreRejected() {
        for (String key : List.of("../private/x.jpg", "a/../../x.jpg", "a/./x.jpg", "a//x.jpg", "a/b/", "..", "///")) {
            assertViolation(product -> media(product).add(Fixtures.media("m-bad", null, "IMAGE", key, 0)));
            assertViolation(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42, "W-BLACK-42",
                    Map.of("size", 42, "color", "black"), key)));
        }
    }

    @Test
    @DisplayName("Storage key bình thường, có dấu cách, dấu chấm trong tên file hoặc dấu / đầu vẫn hợp lệ")
    void ordinaryStorageKeysAreAccepted() {
        for (String key : List.of("products/watch/hero shot.jpg", "/products/a.b/c..jpg", ".hidden/x.jpg", "x")) {
            ProductPayload payload = read(mutate(product ->
                    media(product).add(Fixtures.media("m-ok", null, "IMAGE", key, 0))));

            assertThat(payload.validated()).isNotNull();
        }
    }

    @Test
    @DisplayName("Thumbnail key rỗng hoặc dài quá 1024 là sai contract, null là hợp lệ")
    void thumbnailKeyIsValidated() {
        assertViolation(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42, "W-BLACK-42",
                Map.of("size", 42, "color", "black"), "")));
        assertViolation(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42, "W-BLACK-42",
                Map.of("size", 42, "color", "black"), "k".repeat(1025))));
        assertThat(read(mutate(product -> skus(product).set(0, Fixtures.sku(Fixtures.SKU_BLACK_42, "W-BLACK-42",
                Map.of("size", 42, "color", "black"), null)))).validated()).isNotNull();
    }

    @Test
    @DisplayName("Chuỗi vượt giới hạn của contract Product (value 500, label 120, alt_text 255, slug 180) là sai contract")
    void overlongStringsAreRejected() {
        assertViolation(product -> product.put("slug", "s".repeat(181)));
        assertViolation(product -> attributes(product).add(
                Fixtures.attribute("x", "X", "TEXT", "v".repeat(501), null)));
        assertViolation(product -> attributes(product).add(
                Fixtures.attribute("y", "Y", "SELECT", "a", "l".repeat(121))));
        assertViolation(product -> media(product).add(altText(
                Fixtures.media("m-alt", null, "IMAGE", "x.jpg", 0), "a".repeat(256))));
        assertViolation(product -> axes(product).get(1).put("options", List.of(
                Fixtures.option("black", "l".repeat(121)), Fixtures.option("white", "Trắng"))));
    }

    private static Map<String, Object> altText(Map<String, Object> media, String altText) {
        media.put("alt_text", altText);
        return media;
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
    private static List<Map<String, Object>> axes(Map<String, Object> product) {
        return (List<Map<String, Object>>) product.get("variant_axes");
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
