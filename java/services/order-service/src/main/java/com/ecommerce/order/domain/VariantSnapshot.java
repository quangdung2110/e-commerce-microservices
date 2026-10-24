package com.ecommerce.order.domain;

import java.util.Map;
import java.util.stream.Collectors;

/** Stable fallback for older Checkout snapshots without an explicit variant label. */
public final class VariantSnapshot {
    private VariantSnapshot() { }

    public static String name(String explicit, Map<String, String> attributes) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String label = attributes.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ": " + e.getValue().strip())
                .collect(Collectors.joining(" / "));
        if (label.isEmpty()) return null;
        int end = Math.min(label.length(), 255);
        if (end < label.length() && Character.isHighSurrogate(label.charAt(end - 1))) end--;
        return label.substring(0, end);
    }
}
