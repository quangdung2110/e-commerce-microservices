package com.ecommerce.pdp.client;

import com.ecommerce.pdp.config.PdpProperties;
import com.ecommerce.pdp.resilience.ContractViolationException;
import com.ecommerce.pdp.resilience.DependencyGuard;
import com.ecommerce.pdp.resilience.UpstreamStatusException;
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

/** Phần dùng chung của ba client: dựng {@link WebClient} và đọc body theo tolerant reader. */
final class UpstreamHttp {

    static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    static final String SERVICE_IDENTITY = "product-detail-bff";
    /** contracts ADR-003: how long the BFF will wait for this call, in milliseconds. */
    static final String TIMEOUT_HEADER = "X-Request-Timeout-Ms";
    private static final long MAX_TIMEOUT_MS = 600_000;

    private UpstreamHttp() {
    }

    /**
     * Một pool kết nối riêng cho mỗi dependency, không nhỏ hơn giới hạn bulkhead để bulkhead
     * là giới hạn đồng thời duy nhất phía BFF (ADR-004 §3). Timeout của response do
     * {@code DependencyGuard} đặt theo deadline; ở đây chỉ chặn connect treo.
     *
     * @param builder builder do Spring cấu hình, mang observation để {@code traceparent}
     *                được truyền đi
     */
    static WebClient build(WebClient.Builder builder, ClientTransports transports, String name, PdpProperties.Upstream settings) {
        ConnectionProvider pool = ConnectionProvider.builder("pdp-" + name)
                .maxConnections(settings.bulkhead())
                .metrics(true)
                .build();
        HttpClient httpClient = transports.register(name, pool, (int) settings.timeout().toMillis(), settings.baseUrl());
        WebClient.Builder configured = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                .filter(timeoutHeader());
        if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) {
            configured.baseUrl(settings.baseUrl());
        }
        WebClient client = configured.build();
        transports.registerProbe(name, client);
        return client;
    }

    /**
     * Gửi timeout mà lời gọi đang chạy dưới ({@code DependencyGuard.EFFECTIVE_TIMEOUT_KEY} trong Reactor
     * context) làm {@code X-Request-Timeout-Ms}, để provider biết BFF chờ bao lâu (contracts ADR-003).
     * Lời gọi ngoài guard, như probe lúc khởi động, không gửi gì.
     */
    static ExchangeFilterFunction timeoutHeader() {
        return (request, next) -> Mono.deferContextual(context -> {
            Duration timeout = context.getOrDefault(DependencyGuard.EFFECTIVE_TIMEOUT_KEY, null);
            if (timeout == null || request.headers().containsKey(TIMEOUT_HEADER)) {
                return next.exchange(request);
            }
            long millis = Math.max(1, Math.min(MAX_TIMEOUT_MS, timeout.toMillis()));
            return next.exchange(ClientRequest.from(request).header(TIMEOUT_HEADER, Long.toString(millis)).build());
        });
    }

    /** Status không phải 2xx thành {@link UpstreamStatusException}; body được bỏ để trả kết nối. */
    static <T> Mono<T> unexpectedStatus(ClientResponse response) {
        int status = response.statusCode().value();
        return response.releaseBody().then(Mono.error(new UpstreamStatusException(status)));
    }

    /** Body rỗng hoặc không parse được là vi phạm contract, không phải lỗi mạng. */
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
                        io.micrometer.core.instrument.Metrics.timer("pdp.client.decode", "type", type.getSimpleName())
                                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                });
    }
}
