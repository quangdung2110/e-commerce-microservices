package com.ecommerce.checkout.client;

import com.ecommerce.checkout.resilience.ContractViolationException;
import com.ecommerce.checkout.resilience.UpstreamStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;

/** What the HTTP clients share: building a {@link WebClient} and reading bodies as a tolerant reader. */
final class UpstreamHttp {

    static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    static final String SERVICE_IDENTITY = "checkout-service";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
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
    static WebClient build(WebClient.Builder builder, String name, String baseUrl, int bulkhead) {
        ConnectionProvider pool = ConnectionProvider.builder("checkout-" + name)
                .maxConnections(bulkhead)
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                .baseUrl(baseUrl)
                .build();
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
                    try {
                        return objectMapper.readValue(bytes, type);
                    } catch (IOException e) {
                        throw new ContractViolationException(type.getSimpleName() + ": unreadable body", e);
                    }
                });
    }

    static Mono<JsonNode> readTree(ClientResponse response, ObjectMapper objectMapper) {
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(() -> new ContractViolationException("empty body")))
                .map(bytes -> {
                    try {
                        return objectMapper.readTree(bytes);
                    } catch (IOException e) {
                        throw new ContractViolationException("unreadable body", e);
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
