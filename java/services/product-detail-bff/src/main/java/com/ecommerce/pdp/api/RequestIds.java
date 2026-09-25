package com.ecommerce.pdp.api;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.http.HttpHeaders;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Request ID và trace ID của một request PDP (ADR-007).
 *
 * <p>{@code X-Request-Id} do gateway hoặc client gửi chỉ được dùng khi khớp
 * {@value #REQUEST_ID_PATTERN}; Inventory và Review yêu cầu 8–128 ký tự, nên chuyển thẳng
 * giá trị client sẽ cho phép người ngoài khiến provider trả {@code 400} và bắn alert cấu
 * hình. Giá trị sai không làm request lỗi, chỉ bị thay bằng trace ID.
 */
final class RequestIds {

    static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private static final Pattern REQUEST_ID = Pattern.compile(REQUEST_ID_PATTERN);
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);

    private RequestIds() {
    }

    static String requestId(HttpHeaders headers, String traceId) {
        String inbound = headers.getFirst("X-Request-Id");
        return inbound != null && REQUEST_ID.matcher(inbound).matches() ? inbound : traceId;
    }

    /**
     * Trace ID của span server hiện tại; nếu tracing không có mặt thì lấy từ
     * {@code traceparent} đầu vào; cuối cùng là một ID ngẫu nhiên 32 hex.
     */
    static String traceId(ContextView reactorContext, HttpHeaders headers) {
        return fromObservation(reactorContext)
                .or(() -> fromTraceparent(headers.getFirst("traceparent")))
                .orElseGet(RequestIds::randomTraceId);
    }

    private static Optional<String> fromObservation(ContextView reactorContext) {
        return reactorContext.<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)
                .map(observation -> observation.getContextView()
                        .<TracingObservationHandler.TracingContext>get(TracingObservationHandler.TracingContext.class))
                .map(TracingObservationHandler.TracingContext::getSpan)
                .map(Span::context)
                .map(context -> context.traceId())
                .filter(RequestIds::isTraceId);
    }

    private static Optional<String> fromTraceparent(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        var matcher = TRACEPARENT.matcher(traceparent.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)).filter(RequestIds::isTraceId) : Optional.empty();
    }

    private static boolean isTraceId(String value) {
        return value != null && value.length() == 32 && !value.equals(ZERO_TRACE_ID);
    }

    /** ThreadLocalRandom thay vì UUID: không chặn event loop chờ entropy. */
    private static String randomTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }
}
