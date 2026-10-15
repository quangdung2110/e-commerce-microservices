package com.ecommerce.order.client;

import com.ecommerce.order.common.RequestCorrelation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public HttpRemote(String baseUrl, Duration connectTimeout, Duration readTimeout, ObjectMapper mapper,
                      String serviceName) {
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.client = clientWith(readTimeout);
        this.mapper = mapper;
        this.serviceName = serviceName;
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

    private RemoteResult execute(HttpMethod method, String path, Map<String, String> headers, JsonNode body) {
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
            return spec.exchange((request, response) -> classify(response.getStatusCode().value(),
                    readBody(response.getBody())), true);
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

    private JsonNode readBody(InputStream stream) {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            return bytes.length == 0 ? null : mapper.readTree(bytes);
        } catch (IOException e) {
            return null;
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
