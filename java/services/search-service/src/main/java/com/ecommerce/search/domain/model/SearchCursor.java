package com.ecommerce.search.domain.model;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.common.SearchException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Opaque {@code search_after} cursor (ADR-007, ADR-012).
 *
 * <p>A cursor is base64 of the last hit's sort values plus a fingerprint of the normalised
 * query. Replaying it against a different query, filter or sort is rejected with
 * {@code INVALID_CURSOR} rather than silently returning a wrong page. Clients must treat
 * it as opaque and never mint one.
 */
public final class SearchCursor {

    /** Current wire generation; bumping it invalidates every cursor already handed out. */
    private static final int VERSION = 1;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public SearchCursor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Encodes the sort values of the last hit of a page. */
    public String encode(SearchQuery query, List<FieldValue> sortValues) {
        List<Object> plain = sortValues.stream().map(SearchCursor::toPlain).toList();
        Map<String, Object> payload = Map.of(
                "v", VERSION,
                "f", fingerprint(query),
                "s", plain);
        try {
            return ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("cursor could not be encoded", e);
        }
    }

    /**
     * Decodes a cursor and verifies it belongs to this exact query. Any decoding problem
     * and any fingerprint mismatch are the same client error: the cursor is not usable
     * here.
     */
    public List<FieldValue> decode(SearchQuery query, String cursor) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(DECODER.decode(cursor), MAP_TYPE);
        } catch (Exception e) {
            throw SearchException.invalidCursor("cursor is not a cursor issued by this API");
        }
        if (!Integer.valueOf(VERSION).equals(asInt(payload.get("v")))) {
            throw SearchException.invalidCursor("cursor was issued by an incompatible API version");
        }
        if (!fingerprint(query).equals(payload.get("f"))) {
            throw SearchException.invalidCursor(
                    "cursor was issued for a different query, filter or sort; restart pagination");
        }
        Object sortValues = payload.get("s");
        if (!(sortValues instanceof List<?> values) || values.isEmpty()) {
            throw SearchException.invalidCursor("cursor carries no sort position");
        }
        return values.stream().map(SearchCursor::toFieldValue).toList();
    }

    private String fingerprint(SearchQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.fingerprintSource().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }

    private static Object toPlain(FieldValue value) {
        if (value.isNull()) {
            return null;
        }
        return switch (value._kind()) {
            case Long -> value.longValue();
            case Double -> value.doubleValue();
            case Boolean -> value.booleanValue();
            default -> value.stringValue();
        };
    }

    private static FieldValue toFieldValue(Object plain) {
        if (plain == null) {
            return FieldValue.NULL;
        }
        if (plain instanceof Integer i) {
            return FieldValue.of(i.longValue());
        }
        if (plain instanceof Long l) {
            return FieldValue.of(l);
        }
        if (plain instanceof Number n) {
            return FieldValue.of(n.doubleValue());
        }
        if (plain instanceof Boolean b) {
            return FieldValue.of(b);
        }
        return FieldValue.of(plain.toString());
    }

    private static Integer asInt(Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }
}
