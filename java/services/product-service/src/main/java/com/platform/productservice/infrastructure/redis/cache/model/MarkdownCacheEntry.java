package com.platform.productservice.infrastructure.redis.cache.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Cache entry for active markdown pricing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MarkdownCacheEntry {

    private String ruleId;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal calculatedPrice;
    private String expiresAt;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * Serializes this entry to a JSON string.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize MarkdownCacheEntry to JSON", e);
        }
    }

    /**
     * Deserializes a JSON string to a MarkdownCacheEntry.
     */
    public static MarkdownCacheEntry fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MarkdownCacheEntry.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize MarkdownCacheEntry from JSON", e);
        }
    }

    /**
     * Calculates the remaining TTL in seconds based on the expiry time.
     * Returns 0 if already expired.
     */
    public long remainingTtlSeconds() {
        if (expiresAt == null || expiresAt.isBlank()) {
            return 0;
        }
        try {
            Instant expiry = Instant.from(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(expiresAt)
            );
            long remaining = Duration.between(Instant.now(), expiry).getSeconds();
            return Math.max(0, remaining);
        } catch (Exception e) {
            return 0;
        }
    }
}
