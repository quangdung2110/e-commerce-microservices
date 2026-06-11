package com.platform.productservice.infrastructure.redis.cache.model;

import com.fasterxml.jackson.annotation.JsonNaming;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an item in the pending inventory sync ZSET queue.
 * ZSET score = eventTimestamp, member = "{skuId}:{eventTimestamp}".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PendingSyncItem {

    private String skuId;
    private String productId;
    private long eventTimestamp;
    private int availableQuantity;
    private String skuStatus;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static final String MEMBER_SEPARATOR = ":";

    /**
     * Returns the ZSET member string in format "{skuId}:{eventTimestamp}".
     */
    public String getMemberString() {
        return skuId + MEMBER_SEPARATOR + eventTimestamp;
    }

    /**
     * Parses the SKU ID and timestamp from a ZSET member string.
     *
     * @param member ZSET member string in format "{skuId}:{eventTimestamp}"
     * @return PendingSyncItem with skuId and eventTimestamp populated, or null if parsing fails
     */
    public static PendingSyncItem fromMemberString(String member) {
        if (member == null || member.isBlank()) {
            return null;
        }
        int lastSep = member.lastIndexOf(MEMBER_SEPARATOR);
        if (lastSep <= 0 || lastSep >= member.length() - 1) {
            return null;
        }
        try {
            String skuId = member.substring(0, lastSep);
            long timestamp = Long.parseLong(member.substring(lastSep + 1));
            return PendingSyncItem.builder()
                    .skuId(skuId)
                    .eventTimestamp(timestamp)
                    .build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Serializes this item to a JSON string for metadata storage.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize PendingSyncItem to JSON", e);
        }
    }

    /**
     * Deserializes a JSON string to a PendingSyncItem.
     */
    public static PendingSyncItem fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PendingSyncItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize PendingSyncItem from JSON", e);
        }
    }
}
