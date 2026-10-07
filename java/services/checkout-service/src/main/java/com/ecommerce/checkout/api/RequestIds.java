package com.ecommerce.checkout.api;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.http.HttpHeaders;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Request ID and trace ID of a Checkout request (interfaces.md, "Header gui cho dependency").
 *
 * <p>{@code X-Request-Id} from the Gateway or a client is only forwarded when it matches
 * {@value #REQUEST_ID_PATTERN}: Inventory and Order require 8 to 128 characters, so passing a
 * client value through would let an outsider make a provider answer {@code 400} and fire a
 * configuration alert. An invalid value never fails the request; it is replaced by the trace ID.
 */
final class RequestIds {

    static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private static final Pattern REQUEST_ID = Pattern.compile(REQUEST_ID_PATTERN);
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);

    private RequestIds() {
    }

    static String requestId(HttpHeaders headers, String traceId) {
        String inbound = headers.getFirst("X-Request-Id");
        return inbound != null && REQUEST_ID.matcher(inbound).matches() ? inbound : traceId;
    }

    /**
     * Trace ID of the current server span; without tracing it comes from the inbound
     * {@code traceparent}, and last from a random 32-hex ID.
     */
    static String traceId(ContextView reactorContext, HttpHeaders headers) {
        return fromObservation(reactorContext).map(TraceContext::traceId).filter(RequestIds::isTraceId)
                .or(() -> fromTraceparent(headers.getFirst("traceparent")))
                .orElseGet(RequestIds::randomTraceId);
    }

    /** {@code traceparent} of the current server span, or the inbound one, or {@code null}. */
    static String traceparent(ContextView reactorContext, HttpHeaders headers) {
        Optional<TraceContext> context = fromObservation(reactorContext)
                .filter(c -> isTraceId(c.traceId()) && c.spanId() != null && c.spanId().length() == 16);
        if (context.isPresent()) {
            TraceContext c = context.get();
            return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.FALSE.equals(c.sampled()) ? "00" : "01");
        }
        String inbound = headers.getFirst("traceparent");
        return inbound != null && TRACEPARENT.matcher(inbound.trim()).matches() ? inbound.trim() : null;
    }

    private static Optional<TraceContext> fromObservation(ContextView reactorContext) {
        return reactorContext.<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)
                .map(observation -> observation.getContextView()
                        .<TracingObservationHandler.TracingContext>get(TracingObservationHandler.TracingContext.class))
                .map(TracingObservationHandler.TracingContext::getSpan)
                .map(Span::context);
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

    /** ThreadLocalRandom, not UUID: never makes the event loop wait for entropy. */
    private static String randomTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }
}
