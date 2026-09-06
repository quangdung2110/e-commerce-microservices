package com.ecommerce.search.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Encoding of a Product attribute entry into exactly one {@code filter_tokens} term,
 * following {@code docs/search/data.md §10.1}.
 *
 * <p>The token is {@code code:type:value} where type is {@code s}, {@code n} or {@code b}.
 * Keeping the type in the token is what stops a SKU from confusing {@code "1"}, {@code 1}
 * and {@code true} while letting filter and facet share a single keyword field.
 *
 * <p>This is an encoding internal to Search. Product is not required to store it, and the
 * token reaches clients only as the opaque {@code FacetOption.value}.
 */
public final class AttributeToken {

    /** Scalar kinds Product may publish as an attribute value. */
    public enum ValueType {
        STRING("s"), NUMBER("n"), BOOLEAN("b");

        private final String marker;

        ValueType(String marker) {
            this.marker = marker;
        }

        public String marker() {
            return marker;
        }

        static Optional<ValueType> fromMarker(String marker) {
            for (ValueType type : values()) {
                if (type.marker.equals(marker)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /** A token decoded back into its three parts, used to render facets. */
    public record Decoded(String code, ValueType type, String value) {
    }

    private AttributeToken() {
    }

    /**
     * Encodes one attribute entry. Returns empty when the value is null or the code is
     * blank — a malformed entry is dropped rather than indexed as a broken token.
     */
    public static Optional<String> encode(String code, Object value) {
        if (code == null || code.isBlank() || value == null) {
            return Optional.empty();
        }
        ValueType type;
        String rendered;
        if (value instanceof Boolean bool) {
            type = ValueType.BOOLEAN;
            rendered = bool ? "true" : "false";
        } else if (value instanceof Number number) {
            type = ValueType.NUMBER;
            rendered = renderNumber(number);
        } else {
            type = ValueType.STRING;
            rendered = foldString(value.toString());
            if (rendered.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(escape(foldString(code)) + ':' + type.marker() + ':' + escape(rendered));
    }

    /**
     * Encodes a whole attribute map into a sorted, duplicate-free token set so that two
     * SKUs with the same attributes always produce byte-identical {@code filter_tokens}.
     */
    public static TreeSet<String> encodeAll(Map<String, Object> attributes) {
        TreeSet<String> tokens = new TreeSet<>();
        if (attributes == null) {
            return tokens;
        }
        attributes.forEach((code, value) -> encode(code, value).ifPresent(tokens::add));
        return tokens;
    }

    /** Inverse of {@link #encode}; empty when the token is not well formed. */
    public static Optional<Decoded> decode(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String code = null;
        String marker = null;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\\' && i + 1 < token.length()) {
                part.append(token.charAt(++i));
            } else if (c == ':' && code == null) {
                code = part.toString();
                part.setLength(0);
            } else if (c == ':' && marker == null) {
                marker = part.toString();
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        if (code == null || marker == null || code.isEmpty()) {
            return Optional.empty();
        }
        String decodedCode = code;
        String decodedValue = part.toString();
        return ValueType.fromMarker(marker).map(type -> new Decoded(decodedCode, type, decodedValue));
    }

    /**
     * Unicode-normalise and case-fold a string the same way for codes and values, so that
     * the index-time token and the token echoed back through {@code filter} always match.
     */
    private static String foldString(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        return normalized.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /** Renders a number canonically so {@code 1}, {@code 1.0} and {@code 1.00} agree. */
    private static String renderNumber(Number number) {
        double asDouble = number.doubleValue();
        if (asDouble == Math.rint(asDouble) && !Double.isInfinite(asDouble)) {
            return Long.toString((long) asDouble);
        }
        return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    }

    /** Escapes the two characters that would otherwise break the three-part split. */
    private static String escape(String raw) {
        return Objects.requireNonNull(raw).replace("\\", "\\\\").replace(":", "\\:");
    }
}
