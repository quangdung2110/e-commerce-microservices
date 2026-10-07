package com.ecommerce.checkout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two hashes must equal what Order recomputes (Order OpenAPI), so the expected values are literal RFC 8785 JSON. */
class HashesTest {

    @Test
    @DisplayName("items_hash is sha256 of the canonical {items:[{quantity,sku_id}]} sorted by sku_id")
    void itemsHash() throws Exception {
        String hash = Hashes.itemsHash(List.of(new Hashes.SkuQuantity("sku-2", 1), new Hashes.SkuQuantity("sku-1", 2)));

        assertThat(hash).isEqualTo(sha256("{\"items\":[{\"quantity\":2,\"sku_id\":\"sku-1\"},"
                + "{\"quantity\":1,\"sku_id\":\"sku-2\"}]}"));
    }

    @Test
    @DisplayName("pricing_hash covers price per SKU, the campaign revision and the total discount")
    void pricingHash() throws Exception {
        String hash = Hashes.pricingHash(List.of(new Hashes.SkuPrice("sku-2", 50_000), new Hashes.SkuPrice("sku-1", 100_000)),
                3, 25_000);

        assertThat(hash).isEqualTo(sha256("{\"items\":[{\"sku_id\":\"sku-1\",\"unit_price_minor\":100000},"
                + "{\"sku_id\":\"sku-2\",\"unit_price_minor\":50000}],"
                + "\"promotion_campaign_revision\":3,\"total_discount_minor\":25000}"));
    }

    @Test
    @DisplayName("Without a voucher the revision and discount are 0")
    void noVoucher() throws Exception {
        assertThat(Hashes.pricingHash(List.of(new Hashes.SkuPrice("a", 1)), 0, 0)).isEqualTo(sha256(
                "{\"items\":[{\"sku_id\":\"a\",\"unit_price_minor\":1}],\"promotion_campaign_revision\":0,"
                        + "\"total_discount_minor\":0}"));
    }

    @Test
    @DisplayName("The order of the input does not change the hash")
    void inputOrderIsIrrelevant() {
        List<Hashes.SkuQuantity> forward = List.of(new Hashes.SkuQuantity("a", 1), new Hashes.SkuQuantity("b", 2),
                new Hashes.SkuQuantity("c", 3));
        List<Hashes.SkuQuantity> backward = new ArrayList<>(forward);
        java.util.Collections.reverse(backward);

        assertThat(Hashes.itemsHash(backward)).isEqualTo(Hashes.itemsHash(forward));
    }

    @Test
    @DisplayName("SKUs sort by UTF-8 bytes, not by UTF-16 code units")
    void skuOrderIsUtf8() {
        // U+1F600 is a surrogate pair (D83D...) in UTF-16 and sorts before U+FF5E there,
        // but its UTF-8 bytes (F0 9F 98 80) sort after U+FF5E's (EF BD 9E).
        String emoji = new String(Character.toChars(0x1F600));
        String fullwidthTilde = "～";

        List<String> sorted = new ArrayList<>(List.of(emoji, fullwidthTilde, "z", "é"));
        sorted.sort(Hashes.SKU_ORDER);

        assertThat(sorted).containsExactly("z", "é", fullwidthTilde, emoji);
    }

    private static String sha256(String canonicalJson) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    }
}
