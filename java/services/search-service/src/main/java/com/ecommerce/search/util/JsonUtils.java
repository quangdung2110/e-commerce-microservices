package com.ecommerce.search.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Static JSON utility that delegates to the configured ObjectMapper.
 *
 * <p>Used in contexts where injection is impractical (e.g. static helper methods
 * invoked from worker scripts). For all other use cases, prefer direct injection
 * of {@link ObjectMapper}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonUtils {

    private static ObjectMapper mapper;

    /**
     * Initialize with the application's ObjectMapper bean.
     * Called once at startup from a configuration class.
     */
    public static void init(ObjectMapper objectMapper) {
        mapper = objectMapper;
    }

    /**
     * Serialize an object to a JSON string.
     */
    public static String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    /**
     * Deserialize a JSON string to an object of the specified class.
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    /**
     * Deserialize a JSON string to an object of the specified type reference
     * (e.g. for generic types like {@code List<String>}).
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return mapper.readValue(json, typeRef);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }
}
