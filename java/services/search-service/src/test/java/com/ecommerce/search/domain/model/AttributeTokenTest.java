package com.ecommerce.search.domain.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Attribute token encoding, {@code docs/search/data.md §10.1}. */
class AttributeTokenTest {

    @Test
    void keepsTheThreeScalarTypesApart() {
        // The whole point of the type marker: "1", 1 and true must not collide.
        String asString = AttributeToken.encode("flag", "1").orElseThrow();
        String asNumber = AttributeToken.encode("flag", 1).orElseThrow();
        String asBoolean = AttributeToken.encode("flag", true).orElseThrow();

        assertThat(asString).isEqualTo("flag:s:1");
        assertThat(asNumber).isEqualTo("flag:n:1");
        assertThat(asBoolean).isEqualTo("flag:b:true");
        assertThat(Map.of(asString, 1, asNumber, 1, asBoolean, 1)).hasSize(3);
    }

    @Test
    void foldsCaseAndWhitespaceSoTheSameValueProducesTheSameToken() {
        assertThat(AttributeToken.encode("Brand", "  Apple   Inc ")).contains("brand:s:apple inc");
    }

    @Test
    void escapesSeparatorsSoAValueWithAColonStillDecodes() {
        String token = AttributeToken.encode("ratio", "16:9").orElseThrow();

        AttributeToken.Decoded decoded = AttributeToken.decode(token).orElseThrow();
        assertThat(decoded.code()).isEqualTo("ratio");
        assertThat(decoded.type()).isEqualTo(AttributeToken.ValueType.STRING);
        assertThat(decoded.value()).isEqualTo("16:9");
    }

    @Test
    void rendersNumbersCanonicallySoOneAndOnePointZeroAgree() {
        assertThat(AttributeToken.encode("size", 1.0)).isEqualTo(AttributeToken.encode("size", 1));
    }

    @Test
    void dropsEntriesThatCannotBecomeAUsableToken() {
        assertThat(AttributeToken.encode("color", null)).isEmpty();
        assertThat(AttributeToken.encode("", "black")).isEmpty();
        assertThat(AttributeToken.encode("color", "   ")).isEmpty();
    }

    @Test
    void encodesAMapIntoASortedDuplicateFreeSet() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("color", "Black");
        attributes.put("brand", "Apple");
        attributes.put("waterproof", true);

        assertThat(AttributeToken.encodeAll(attributes))
                .containsExactly("brand:s:apple", "color:s:black", "waterproof:b:true");
    }

    @Test
    void rejectsTokensThatAreNotWellFormed() {
        assertThat(AttributeToken.decode("brand")).isEmpty();
        assertThat(AttributeToken.decode("brand:x:apple")).isEmpty();
        assertThat(AttributeToken.decode("")).isEmpty();
    }
}
