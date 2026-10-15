package com.ecommerce.order.client;

import com.ecommerce.order.common.RequestCorrelation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Minimal JSON-over-HTTP executor that classifies every response into
 * {@link RemoteResult}: 2xx accepted; non-retryable 4xx durable rejection; 5xx,
 * retryable problems and transport failures unknown.
 */
public class HttpRemote {

    private static final Logger log = LoggerFactory.getLogger(HttpRemote.class);

    /** Budget kept back for Order's own work when a call runs on a request thread (ADR-003 rule 3.2). */
    static final Duration RESERVE = Duration.ofMillis(500);
    /** Below this a call is not started: there is no time for a connect and a response. */
    static final Duration FLOOR = Duration.ofMillis(200);

    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String serviceName;
    private final String dependency;
    private final MeterRegistry meters;
    private final Semaphore permits;

    public HttpRemote(String baseUrl, Duration connectTimeout, Duration readTimeout, ObjectMapper mapper,
                      String serviceName) {
        this("unnamed", baseUrl, connectTimeout, readTimeout, mapper, serviceName, null, Integer.MAX_VALUE);
    }

    /**
     * @param dependency    metric label of the provider (inventory, promotion, payment)
     * @param meters        may be {@code null}: no metrics
     * @param maxConcurrent calls in flight to this provider at once; more are not sent and come back as
     *                      UNKNOWN {@code BULKHEAD_FULL}, which the saga replays later (Order ADR-024)
     */
    public HttpRemote(String dependency, String baseUrl, Duration connectTimeout, Duration readTimeout,
                      ObjectMapper mapper, String serviceName, MeterRegistry meters, int maxConcurrent) {
        this.dependency = dependency;
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.client = clientWith(readTimeout);
        this.mapper = mapper;
        this.serviceName = serviceName;
        this.meters = meters;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    private RestClient clientWith(Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public RemoteResult post(String path, Map<String, String> headers, JsonNode body) {
        return execute(HttpMethod.POST, path, headers, body);
    }

    public RemoteResult get(String path, Map<String, String> headers) {
        return execute(HttpMethod.GET, path, headers, null);
    }

    /**
     * One call under the provider's bulkhead, timed and counted per dependency. The outcome label is the
     * classification (ACCEPTED, REJECTED, UNKNOWN); the status label is only the class of the HTTP status,
     * so the metric stays low-cardinality.
     */
    private RemoteResult execute(HttpMethod method, String path, Map<String, String> headers, JsonNode body) {
        if (!permits.tryAcquire()) {
            log.warn("{} {} {} not sent: the bulkhead of {} is full", dependency, method, path, dependency);
            RemoteResult full = RemoteResult.unknown("BULKHEAD_FULL");
            record(method, full, 0);
            return full;
        }
        long started = System.nanoTime();
        RemoteResult result = null;
        try {
            result = send(method, path, headers, body);
            return result;
        } finally {
            permits.release();
            record(method, result, System.nanoTime() - started);
        }
    }

    private void record(HttpMethod method, RemoteResult result, long nanos) {
        if (meters == null) {
            return;
        }
        String outcome = result == null ? "UNKNOWN" : result.kind().name();
        String status = result == null || result.httpStatus() <= 0 ? "none" : (result.httpStatus() / 100) + "xx";
        meters.timer("order.remote.http", "dependency", dependency, "method", method.name(),
                "outcome", outcome, "status", status).record(nanos, TimeUnit.NANOSECONDS);
    }

    private RemoteResult send(HttpMethod method, String path, Map<String, String> headers, JsonNode body) {
        // contracts ADR-003: the timeout is the configured one, kept under the caller's remaining
        // budget when a request is being served. The same value is sent so the provider knows it.
        Duration effective = RequestCorrelation.effectiveTimeout(readTimeout, RESERVE);
        if (effective.compareTo(FLOOR) < 0) {
            log.warn("{} {} not sent: the caller's deadline leaves {} ms", method, path, effective.toMillis());
            return RemoteResult.unknown("DEADLINE_EXHAUSTED");
        }
        try {
            RestClient.RequestBodySpec spec = (effective.equals(readTimeout) ? client : clientWith(effective))
                    .method(method).uri(path)
                    .header("X-Service-Identity", serviceName)
                    .header(RequestCorrelation.TIMEOUT_HEADER, Long.toString(effective.toMillis()))
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            headers.forEach(spec::header);
            propagate(spec, headers);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsBytes(body));
            }
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String retryAfter = response.getHeaders().getFirst("Retry-After");
                if (retryAfter != null && (status == 429 || status == 503)) {
                    // The saga owns the retry cadence (Temporal backoff and command replay); the hint is
                    // only recorded so an operator can see why a provider asked to be left alone.
                    log.warn("{} {} {} answered {} with Retry-After {}", dependency, method, path, status, retryAfter);
                }
                JsonNode answer;
                try {
                    answer = readBody(response.getBody());
                } catch (UnreadableBody unreadable) {
                    if (status >= 200 && status < 300) {
                        // The status line says the provider accepted the command, but nothing usable came with it. The saga reads
                        // fields from the body of every accepted answer, so "accepted without a body" would be a NullPointerException
                        // there; UNKNOWN sends it to query or replay the same command identity instead (Order ADR-025).
                        log.warn("{} {} {} answered {} with a body that could not be read: {}", dependency, method, path, status,
                                unreadable.getMessage());
                        return new RemoteResult(RemoteResult.Kind.UNKNOWN, status, "BODY_UNREADABLE", null);
                    }
                    answer = null;
                }
                return classify(status, answer);
            }, true);
        } catch (Exception e) {
            log.warn("{} {} transport failure: {}", method, path, e.toString());
            return RemoteResult.unknown("TRANSPORT_" + e.getClass().getSimpleName());
        }
    }

    /**
     * contracts ADR-002. On the request thread the inbound context is passed through; Temporal
     * activities and other work without a request get a new {@code X-Request-Id} per call and no
     * {@code traceparent}, because Order has no tracer and must not invent a trace ID. A header the
     * caller already set is never duplicated.
     */
    private static void propagate(RestClient.RequestBodySpec spec, Map<String, String> explicit) {
        RequestCorrelation current = RequestCorrelation.current();
        if (!hasHeader(explicit, RequestCorrelation.REQUEST_ID_HEADER)) {
            spec.header(RequestCorrelation.REQUEST_ID_HEADER,
                    current != null ? current.requestId() : RequestCorrelation.newRequestId());
        }
        if (current != null && current.traceparent() != null
                && !hasHeader(explicit, RequestCorrelation.TRACEPARENT_HEADER)) {
            spec.header(RequestCorrelation.TRACEPARENT_HEADER, current.traceparent());
            if (current.tracestate() != null && !hasHeader(explicit, RequestCorrelation.TRACESTATE_HEADER)) {
                spec.header(RequestCorrelation.TRACESTATE_HEADER, current.tracestate());
            }
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    /** Raised when a response body that should be JSON cannot be read or parsed (cut short, too slow, not JSON). */
    private static final class UnreadableBody extends RuntimeException {
        UnreadableBody(Throwable cause) {
            super(cause.toString(), cause);
        }
    }

    /** {@code null} for an empty body; throws {@link UnreadableBody} when the bytes are missing or are not JSON. */
    private JsonNode readBody(InputStream stream) {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            return bytes.length == 0 ? null : mapper.readTree(bytes);
        } catch (IOException e) {
            throw new UnreadableBody(e);
        }
    }

    static RemoteResult classify(int status, JsonNode body) {
        if (status >= 200 && status < 300) {
            return RemoteResult.accepted(status, body);
        }
        String code = body == null ? null : body.path("code").asText(null);
        boolean retryable = body != null && body.path("retryable").asBoolean(false);
        if (status >= 500 || status == 408 || status == 429 || retryable) {
            return new RemoteResult(RemoteResult.Kind.UNKNOWN, status, code == null ? "HTTP_" + status : code, body);
        }
        if (status == 404 && code == null) {
            code = "NOT_FOUND";
        }
        return RemoteResult.rejected(status, code == null ? "HTTP_" + status : code, body);
    }
}
