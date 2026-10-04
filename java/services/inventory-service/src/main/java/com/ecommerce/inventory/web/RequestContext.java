package com.ecommerce.inventory.web;

/**
 * Authenticated caller and trace identifiers of the current request.
 *
 * @param namespace    idempotency namespace: the service identity, or {@code admin} for
 *                     every ADMIN user (ADR-027)
 * @param actorType    {@code SERVICE} or {@code USER}
 * @param actorId      service identity or {@code X-User-Id}
 * @param actorRole    {@code ADMIN} for users, null for services
 * @param sessionId    {@code X-User-Session-Id}, users only
 * @param correlationId {@code X-Correlation-ID} as received (may be null or invalid)
 * @param causationId  {@code X-Causation-ID} as received
 * @param path         request path, used as problem {@code instance}
 */
public record RequestContext(
        String namespace,
        String actorType,
        String actorId,
        String actorRole,
        String sessionId,
        String correlationId,
        String causationId,
        String path) {

    public static final String ADMIN_NAMESPACE = "admin";

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    public static RequestContext current() {
        return CURRENT.get();
    }

    static void set(RequestContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }

    public boolean hasValidCorrelationId() {
        return validId(correlationId);
    }

    static boolean validId(String value) {
        return value != null && value.length() >= 8 && value.length() <= 128 && !value.isBlank();
    }
}
