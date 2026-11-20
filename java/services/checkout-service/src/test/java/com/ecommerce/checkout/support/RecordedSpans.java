package com.ecommerce.checkout.support;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every span the service ends, as the OpenTelemetry SDK saw it. Boot adds each {@link SpanProcessor} bean to
 * the tracer provider, so this records the real spans (names, parents, attributes) without an exporter.
 */
public final class RecordedSpans implements SpanProcessor {

    /** An ended span; {@code parentId} is empty for a root. */
    public record Span(String name, String traceId, String id, String parentId, Map<String, String> attributes,
                       boolean error) {
    }

    private final List<Span> ended = new CopyOnWriteArrayList<>();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData data = span.toSpanData();
        Map<String, String> attributes = new LinkedHashMap<>();
        data.getAttributes().forEach((AttributeKey<?> key, Object value) ->
                attributes.put(key.getKey(), String.valueOf(value)));
        ended.add(new Span(data.getName(), data.getTraceId(), data.getSpanId(),
                data.getParentSpanContext().isValid() ? data.getParentSpanId() : "",
                Map.copyOf(attributes), data.getStatus().getStatusCode() == StatusCode.ERROR));
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    public void clear() {
        ended.clear();
    }

    public List<Span> all() {
        return List.copyOf(ended);
    }
}
