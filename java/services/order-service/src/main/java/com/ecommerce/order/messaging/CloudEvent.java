package com.ecommerce.order.messaging;

import com.ecommerce.order.common.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Parsed structured CloudEvent plus the SHA-256 of its exact bytes. */
public record CloudEvent(String id, String type, String source, String subject, String dataschema,
                         String datacontenttype, String specversion, JsonNode data, JsonNode raw, String sha256) {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Returns null when the record is not a JSON object CloudEvent. */
    public static CloudEvent parse(ObjectMapper mapper, String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode raw = mapper.readTree(value);
            if (raw == null || !raw.isObject()) {
                return null;
            }
            return new CloudEvent(text(raw, "id"), text(raw, "type"), text(raw, "source"), text(raw, "subject"),
                    text(raw, "dataschema"), text(raw, "datacontenttype"), text(raw, "specversion"),
                    raw.get("data"), raw, sha256(value));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasUuidId() {
        return id != null && UUID_PATTERN.matcher(id).matches();
    }

    public boolean dataIsObject() {
        return data != null && data.isObject();
    }

    public long extensionLong(String name) {
        return raw.path(name).asLong(-1);
    }

    public String extensionText(String name) {
        return text(raw, name);
    }

    /** Canonical digest of the event, stable across key order/whitespace of redelivery. */
    public String canonicalDigest() {
        return CanonicalJson.digest(raw);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
