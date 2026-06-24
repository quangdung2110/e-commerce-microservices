package com.platform.inventoryservice.common;

import java.util.UUID;

public final class CorrelationIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdContext() {
    }

    public static void set(String id) {
        CURRENT.set(id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
    }

    public static String get() {
        String id = CURRENT.get();
        if (id == null) {
            id = UUID.randomUUID().toString();
            CURRENT.set(id);
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}