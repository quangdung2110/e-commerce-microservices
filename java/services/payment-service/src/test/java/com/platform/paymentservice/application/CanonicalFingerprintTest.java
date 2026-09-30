package com.platform.paymentservice.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalFingerprintTest {
    @Test
    void map_order_does_not_change_the_fingerprint() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("order_id", "10001");
        first.put("amount", Map.of("currency", "VND", "amount_minor", 100L));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("amount", Map.of("amount_minor", 100L, "currency", "VND"));
        second.put("order_id", "10001");
        CanonicalFingerprint fingerprint = new CanonicalFingerprint();
        assertThat(fingerprint.sha256(new CanonicalCommand(IdempotencyAction.INITIATE, Map.of(), first)))
                .isEqualTo(fingerprint.sha256(new CanonicalCommand(IdempotencyAction.INITIATE, Map.of(), second)));
    }

    @Test
    void action_path_and_array_order_are_part_of_the_fingerprint() {
        CanonicalFingerprint fingerprint = new CanonicalFingerprint();
        String original = fingerprint.sha256(new CanonicalCommand(
                IdempotencyAction.REFUND, Map.of("transactionId", "TXN_A"), Map.of("lines", List.of("a", "b"))));
        assertThat(fingerprint.sha256(new CanonicalCommand(
                IdempotencyAction.REFUND, Map.of("transactionId", "TXN_B"), Map.of("lines", List.of("a", "b")))))
                .isNotEqualTo(original);
        assertThat(fingerprint.sha256(new CanonicalCommand(
                IdempotencyAction.REFUND, Map.of("transactionId", "TXN_A"), Map.of("lines", List.of("b", "a")))))
                .isNotEqualTo(original);
    }
}
