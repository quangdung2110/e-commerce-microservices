package com.platform.productservice.infrastructure.event;

/**
 * Trace context của luồng đang chạy, để CloudEvent mang {@code traceparent} và
 * {@code correlationid} khi có (contracts/conventions/tracing.md).
 *
 * <p>HTTP filter và Kafka consumer set giá trị này; luồng nào không có context
 * thì event bỏ qua hai extension đó.
 */
public final class EventContext {

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private EventContext() {
    }

    public static void set(String traceparent, String correlationId) {
        CURRENT.set(new Holder(traceparent, correlationId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String traceparent() {
        Holder holder = CURRENT.get();
        return holder == null ? null : holder.traceparent();
    }

    public static String correlationId() {
        Holder holder = CURRENT.get();
        return holder == null ? null : holder.correlationId();
    }

    private record Holder(String traceparent, String correlationId) {
    }
}
