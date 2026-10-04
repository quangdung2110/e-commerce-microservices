package com.ecommerce.inventory.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical request fingerprint: SHA-256 of operation, path parameters and body with
 * object keys sorted. The actor is deliberately excluded so every ADMIN shares one
 * namespace (ADR-027).
 */
@Component
public class Fingerprints {

    private final ObjectMapper objectMapper;

    public Fingerprints(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String of(String operation, Map<String, String> pathParameters, Object body) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("operation", operation);
        ObjectNode path = root.putObject("path");
        new TreeMap<>(pathParameters).forEach(path::put);
        root.set("body", body == null ? JsonNodeFactory.instance.nullNode() : objectMapper.valueToTree(body));
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(canonical(root));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot fingerprint command", e);
        }
    }

    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isNull()) {
                    sorted.put(field.getKey(), canonical(field.getValue()));
                }
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
