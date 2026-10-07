package com.ecommerce.checkout.service;

import com.ecommerce.checkout.crypto.EnvelopeCipher;
import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialization and envelope encryption of the four things Checkout keeps in Redis values: the
 * snapshot, the Order payload, the Preview replay response and a stored problem. The purpose string
 * is authenticated data, so a ciphertext cannot be moved to another field.
 */
@Component
public class Codec {

    static final String SNAPSHOT = "snapshot";
    static final String PAYLOAD = "submit-payload";
    static final String PREVIEW_RESPONSE = "preview-response";
    static final String PROBLEM = "problem";

    private final EnvelopeCipher cipher;
    private final ObjectMapper mapper;

    public Codec(EnvelopeCipher cipher, ObjectMapper mapper) {
        this.cipher = cipher;
        this.mapper = mapper;
    }

    public String keyVersion() {
        return cipher.activeVersion();
    }

    public String encryptSnapshot(Snapshot snapshot) {
        return cipher.encrypt(SNAPSHOT, write(snapshot));
    }

    public Snapshot decryptSnapshot(String ciphertext) {
        return read(cipher.decrypt(SNAPSHOT, ciphertext), Snapshot.class);
    }

    public String encryptPayload(String payloadJson) {
        return cipher.encrypt(PAYLOAD, payloadJson);
    }

    public String decryptPayload(String ciphertext) {
        return cipher.decrypt(PAYLOAD, ciphertext);
    }

    /** The raw checkout token carried by an Order payload, used to rebuild a {@code status_url}. */
    public String tokenOf(String payloadCiphertext) {
        return payloadTree(decryptPayload(payloadCiphertext)).path("checkout_token").asText();
    }

    public JsonNode payloadTree(String payloadJson) {
        return read(payloadJson, JsonNode.class);
    }

    public String encryptPreviewResponse(String responseJson) {
        return cipher.encrypt(PREVIEW_RESPONSE, responseJson);
    }

    public String decryptPreviewResponse(String ciphertext) {
        return cipher.decrypt(PREVIEW_RESPONSE, ciphertext);
    }

    /** Stores the problem without request-specific data; {@code instance} and {@code trace_id} are rebuilt per request. */
    public String encryptProblem(Problem problem) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", problem.code().name());
        node.put("status", problem.status());
        node.put("detail", problem.detail());
        node.set("extensions", mapper.valueToTree(problem.extensions()));
        return cipher.encrypt(PROBLEM, write(node));
    }

    public Problem decryptProblem(String ciphertext) {
        JsonNode node = read(cipher.decrypt(PROBLEM, ciphertext), JsonNode.class);
        ProblemCode code = ProblemCode.valueOf(node.path("code").asText());
        Map<String, Object> extensions = new LinkedHashMap<>();
        node.path("extensions").fields().forEachRemaining(entry ->
                extensions.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
        return new Problem(code, node.path("status").asInt(code.status()), node.path("detail").asText(code.title()),
                extensions, null);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + type.getSimpleName(), e);
        }
    }
}
