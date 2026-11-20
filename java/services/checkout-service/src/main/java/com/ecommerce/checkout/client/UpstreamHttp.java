package com.ecommerce.checkout.client;

import com.ecommerce.checkout.resilience.ContractViolationException;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.resilience.UpstreamStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.time.Duration;

/** What the HTTP clients share: building a {@link WebClient} and reading bodies as a tolerant reader. */
final class UpstreamHttp {

    static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    static final String SERVICE_IDENTITY = "checkout-service";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** contracts ADR-003: how long Checkout will wait for this call, in milliseconds. */
    static final String TIMEOUT_HEADER = "X-Request-Timeout-Ms";
    private static final long MAX_TIMEOUT_MS = 600_000;
    static final int CONNECT_TIMEOUT_MILLIS = 1000;

    private UpstreamHttp() {
    }

    /**
     * One connection pool per dependency, not smaller than the bulkhead so the bulkhead is the only
     * concurrency limit on the Checkout side (ADR-014). Response timeouts are set by
     * {@code DependencyGuard} from the remaining deadline; here only a hung connect is bounded.
     *
     * @param builder Spring's builder, which carries the observation that propagates {@code traceparent}
     */
    static WebClient build(WebClient.Builder builder, ClientTransports transports, String name, String baseUrl, int bulkhead) {
        ConnectionProvider pool = ConnectionProvider.builder("checkout-" + name)
                .maxConnections(bulkhead)
                .metrics(true)
                .build();
        HttpClient httpClient = transports.register(name, pool, CONNECT_TIMEOUT_MILLIS, baseUrl);
        WebClient client = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                .filter(timeoutHeader())
                .baseUrl(baseUrl)
                .build();
        transports.registerProbe(name, client);
        return client;
    }

    /**
     * Sends the timeout the call runs under ({@code DependencyGuard.EFFECTIVE_TIMEOUT_KEY} in the Reactor
     * context) as {@code X-Request-Timeout-Ms}, so the provider knows how long Checkout waits (contracts
     * ADR-003). A call made outside the guard, such as a startup probe, sends nothing.
     */
    static ExchangeFilterFunction timeoutHeader() {
        return (request, next) -> Mono.deferContextual(context -> {
            Duration timeout = context.getOrDefault(DependencyGuard.EFFECTIVE_TIMEOUT_KEY, null);
            if (timeout == null || request.headers().containsHeader(TIMEOUT_HEADER)) {
                return next.exchange(request);
            }
            long millis = Math.max(1, Math.min(MAX_TIMEOUT_MS, timeout.toMillis()));
            return next.exchange(ClientRequest.from(request).header(TIMEOUT_HEADER, Long.toString(millis)).build());
        });
    }

    /** Non-2xx becomes {@link UpstreamStatusException}; the body is released to return the connection. */
    static <T> Mono<T> unexpectedStatus(ClientResponse response) {
        int status = response.statusCode().value();
        return response.releaseBody().then(Mono.error(new UpstreamStatusException(status)));
    }

    /** Like {@link #unexpectedStatus} but keeps the provider's problem {@code code} when it sent one. */
    static <T> Mono<T> unexpectedStatusWithCode(ClientResponse response, ObjectMapper objectMapper) {
        int status = response.statusCode().value();
        return response.bodyToMono(byte[].class)
                .map(bytes -> problemCode(bytes, objectMapper))
                .defaultIfEmpty("")
                .flatMap(code -> Mono.<T>error(new UpstreamStatusException(status, code.isEmpty() ? null : code)));
    }

    /** An empty or unreadable body is a contract violation, not a network failure. */
    static <T> Mono<T> readBody(ClientResponse response, Class<T> type, ObjectMapper objectMapper) {
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(() -> new ContractViolationException(type.getSimpleName() + ": empty body")))
                .map(bytes -> {
                    long started = System.nanoTime();
                    try {
                        return objectMapper.readValue(bytes, type);
                    } catch (IOException e) {
                        throw new ContractViolationException(type.getSimpleName() + ": unreadable body", e);
                    } finally {
                        io.micrometer.core.instrument.Metrics.timer("checkout.client.decode", "type", type.getSimpleName())
                                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                });
    }

    static Mono<JsonNode> readTree(ClientResponse response, ObjectMapper objectMapper) {
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(() -> new ContractViolationException("empty body")))
                .map(bytes -> {
                    long started = System.nanoTime();
                    try {
                        return objectMapper.readTree(bytes);
                    } catch (IOException e) {
                        throw new ContractViolationException("unreadable body", e);
                    } finally {
                        io.micrometer.core.instrument.Metrics.timer("checkout.client.decode", "type", "json")
                                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                });
    }

    private static String problemCode(byte[] body, ObjectMapper objectMapper) {
        try {
            JsonNode code = objectMapper.readTree(body).path("code");
            return code.isTextual() ? code.asText() : "";
        } catch (IOException e) {
            return "";
        }
    }
}
