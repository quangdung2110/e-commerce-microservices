package com.ecommerce.search.util;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes and decodes cursor values for cursor-based pagination.
 *
 * <p>The cursor format is: {@code base64url(JSON([sort_value_1, sort_value_2, ..., sku_id]))}.
 *
 * <p>The sort values come from the last hit of the previous Elasticsearch response.
 * The tie-breaker {@code sku_id} is always the last element in the sort array,
 * ensuring stable pagination via {@code search_after}.
 *
 * <p>Uses URL-safe Base64 (no padding) so the cursor can be used directly
 * in URL query strings without additional encoding.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private final ObjectMapper objectMapper;

    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    /**
     * Encode a list of {@link FieldValue} sort values into a base64url cursor string.
     *
     * <p>Format: {@code base64url(JSON([sort_value_1, sort_value_2, ..., sku_id]))}.
     *
     * @param sortValues the sort values from the last hit (typically {@code [score_or_price, sku_id]})
     * @return a base64url-encoded cursor string, or {@code null} if sortValues is null/empty
     * @throws InvalidCursorException if encoding fails
     */
    public String encode(Integer fromOffset) {
        if (fromOffset == null) {
            return null;
        }
        try {
            String str = String.valueOf(fromOffset);
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(str.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to encode cursor", e);
            throw new InvalidCursorException("Failed to encode cursor: " + e.getMessage(), e);
        }
    }

    /**
     * Decode a base64url cursor string back into a list of {@link FieldValue} sort values
     * for use as Elasticsearch {@code search_after}.
     *
     * @param cursor the base64url-encoded cursor string
     * @return the decoded sort values, or {@code null} if cursor is null/blank
     * @throws InvalidCursorException if the cursor cannot be decoded or parsed
     */
    public Integer decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            log.warn("Cursor is not valid base64: {}", cursor, e);
            throw new InvalidCursorException("Cursor is not valid base64: " + e.getMessage(), e);
        }
        
        try {
            String str = new String(decoded, StandardCharsets.UTF_8);
            return Integer.parseInt(str);
        } catch (Exception e) {
            log.warn("Invalid cursor: {}", cursor, e);
            throw new InvalidCursorException("Invalid cursor format: " + e.getMessage(), e);
        }
    }
}
