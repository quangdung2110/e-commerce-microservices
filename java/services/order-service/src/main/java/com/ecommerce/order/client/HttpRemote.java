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

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String serviceName;

    public HttpRemote(String baseUrl, Duration connectTimeout, Duration readTimeout, ObjectMapper mapper,
                      String serviceName) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
        this.serviceName = serviceName;
    }

    public RemoteResult post(String path, Map<String, String> headers, JsonNode body) {
        return execute(HttpMethod.POST, path, headers, body);
    }

    public RemoteResult get(String path, Map<String, String> headers) {
        return execute(HttpMethod.GET, path, headers, null);
    }

    private RemoteResult execute(HttpMethod method, String path, Map<String, String> headers, JsonNode body) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path)
                    .header("X-Service-Identity", serviceName)
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
