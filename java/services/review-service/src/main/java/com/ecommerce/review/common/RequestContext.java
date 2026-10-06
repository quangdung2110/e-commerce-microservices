package com.ecommerce.review.common;

/**
 * The correlation identifier of the running request or consumer record.
 *
 * <p>It becomes the CloudEvents {@code correlationid} of every outbox row written on
 * that thread, and the {@code trace_id} of every Problem body. A thread with no context
 * simply omits both ({@code docs/review/interfaces.md 4}).
 */
public final class RequestContext {

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            CORRELATION_ID.remove();
        } else {
            CORRELATION_ID.set(correlationId.length() > 128 ? correlationId.substring(0, 128) : correlationId);
        }
    }

    public static String correlationId() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }
}
