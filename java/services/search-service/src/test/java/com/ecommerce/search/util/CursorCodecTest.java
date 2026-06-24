package com.ecommerce.search.util;

import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CursorCodec}.
 */
class CursorCodecTest {

    private CursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CursorCodec(new ObjectMapper());
    }

    @Test
    @DisplayName("Encode and decode round-trip preserves values for Integer")
    void roundTripInteger() {
        Integer original = 15;

        String encoded = codec.encode(original);
        assertThat(encoded).isNotNull().isNotBlank();

        Integer decoded = codec.decode(encoded);
        assertThat(decoded).isEqualTo(15);
    }

    @Test
    @DisplayName("Decode null returns null")
    void decodeNull() {
        assertThat(codec.decode(null)).isNull();
    }

    @Test
    @DisplayName("Decode empty string returns null")
    void decodeEmpty() {
        assertThat(codec.decode("")).isNull();
    }

    @Test
    @DisplayName("Decode blank string returns null")
    void decodeBlank() {
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    @DisplayName("Encode null returns null")
    void encodeNull() {
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    @DisplayName("Invalid base64 throws InvalidCursorException")
    void invalidBase64() {
        assertThatThrownBy(() -> codec.decode("not-valid-base64!!!"))
            .isInstanceOf(InvalidCursorException.class)
            .hasMessageContaining("base64");
    }

    @Test
    @DisplayName("Valid base64 but invalid integer throws InvalidCursorException")
    void validBase64InvalidInteger() {
        String notInt = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not an integer".getBytes());
        assertThatThrownBy(() -> codec.decode(notInt))
            .isInstanceOf(InvalidCursorException.class)
            .hasMessageContaining("cursor format");
    }

}
