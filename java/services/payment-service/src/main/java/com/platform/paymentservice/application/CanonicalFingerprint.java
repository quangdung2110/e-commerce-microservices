package com.platform.paymentservice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CanonicalFingerprint {
    public String sha256(CanonicalCommand command) {
        String canonical = render(Map.of(
                "action", command.action().name(),
                "path_parameters", command.pathParameters(),
                "body", command.body()));
        try {
            return HexFormatHolder.HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String render(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof Number number) return number.toString();
        if (value instanceof Enum<?> enumeration) return quote(enumeration.name());
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) result.append(',');
                result.append(quote(entries.get(i).getKey().toString())).append(':')
                        .append(render(entries.get(i).getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder("[");
            int index = 0;
            for (Object item : iterable) {
                if (index++ > 0) result.append(',');
                result.append(render(item));
            }
            return result.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported canonical value: " + value.getClass().getName());
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private static final class HexFormatHolder {
        private static final java.util.HexFormat HEX = java.util.HexFormat.of();
    }
}
