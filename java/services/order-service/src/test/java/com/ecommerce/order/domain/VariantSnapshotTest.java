package com.ecommerce.order.domain;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VariantSnapshotTest {
    @Test void purchaseAttributesProvideStableLabelWithoutCurrentCatalog() {
        assertThat(VariantSnapshot.name(null, Map.of("config", "Review 1", "color", "QA 1")))
                .isEqualTo("color: QA 1 / config: Review 1");
        assertThat(VariantSnapshot.name("Đỏ / M", Map.of("color", "blue"))).isEqualTo("Đỏ / M");
        assertThat(VariantSnapshot.name(" ", Map.of("empty", " "))).isNull();
        assertThat(VariantSnapshot.name(null, Map.of("long", "x".repeat(300)))).hasSize(255);
    }
}
