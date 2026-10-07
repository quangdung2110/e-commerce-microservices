package com.ecommerce.review.dto;

import com.ecommerce.review.common.ReviewException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code PATCH /api/v1/reviews/{review_id}}, parsed from the raw JSON body.
 *
 * <p>The contract distinguishes three cases per field and a bound record cannot: an
 * absent field is preserved, {@code content: null} clears the text, an absent
 * {@code media_asset_ids} preserves the attachments and an explicit {@code []} removes
 * them all. Binding to a record would collapse "absent" and "null" into the same Java
 * null and silently clear the review body on every rating-only edit.
 *
 * <p>A supplied media list is a complete replacement made of newly uploaded, unattached
 * assets; v1 does not support reordering attachments that are being kept.
 */
public record PatchReviewRequest(
        Patch<Integer> rating,
        Patch<String> content,
        Patch<List<UUID>> mediaAssetIds) {

    private static final Set<String> ALLOWED = Set.of("rating", "content", "media_asset_ids");
    private static final int MAX_MEDIA = 9;
    private static final int MAX_CONTENT = 5000;

    public static PatchReviewRequest from(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ReviewException.invalid("body", "a PATCH body must be a JSON object");
        }
        if (body.isEmpty()) {
            throw ReviewException.invalid("body", "a PATCH body must change at least one field");
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!ALLOWED.contains(name)) {
                throw ReviewException.invalid(name, "the field is not part of this request");
            }
        });
        return new PatchReviewRequest(readRating(body), readContent(body), readMedia(body));
    }

    private static Patch<Integer> readRating(JsonNode body) {
        if (!body.has("rating")) {
            return Patch.absent();
        }
        JsonNode node = body.get("rating");
        if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > 5) {
            throw ReviewException.invalid("rating", "rating must be an integer between 1 and 5");
        }
        return Patch.of(node.asInt());
    }

    private static Patch<String> readContent(JsonNode body) {
        if (!body.has("content")) {
            return Patch.absent();
        }
        JsonNode node = body.get("content");
        if (node.isNull()) {
            // Present and null: the documented way to clear the text.
            return Patch.of(null);
        }
        if (!node.isTextual()) {
            throw ReviewException.invalid("content", "content must be a string or null");
        }
        String value = node.asText();
        if (value.isBlank() || value.length() > MAX_CONTENT) {
            throw ReviewException.invalid("content",
                    "content must be non-blank and at most " + MAX_CONTENT + " characters");
        }
        return Patch.of(value);
    }

    private static Patch<List<UUID>> readMedia(JsonNode body) {
        if (!body.has("media_asset_ids")) {
            return Patch.absent();
        }
        JsonNode node = body.get("media_asset_ids");
        if (!node.isArray()) {
            throw ReviewException.invalid("media_asset_ids",
                    "media_asset_ids must be an array of asset identifiers");
        }
        if (node.size() > MAX_MEDIA) {
            throw ReviewException.invalid("media_asset_ids",
                    "a review carries at most " + MAX_MEDIA + " attachments");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw ReviewException.invalid("media_asset_ids", "every entry must be an asset identifier");
            }
            try {
                ids.add(UUID.fromString(element.asText()));
            } catch (IllegalArgumentException e) {
                throw ReviewException.invalid("media_asset_ids", "every entry must be an asset identifier");
            }
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw ReviewException.invalid("media_asset_ids", "asset identifiers must be unique");
        }
        return Patch.of(List.copyOf(ids));
    }
}
