package com.ecommerce.pdp.client;

import com.ecommerce.pdp.config.PdpProperties;
import com.ecommerce.pdp.resilience.ContractViolationException;
import com.ecommerce.pdp.resilience.UpstreamStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;

/** Phần dùng chung của ba client: dựng {@link WebClient} và đọc body theo tolerant reader. */
final class UpstreamHttp {

    static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    static final String SERVICE_IDENTITY = "product-detail-bff";

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
    static WebClient build(WebClient.Builder builder, String name, PdpProperties.Upstream settings) {
        ConnectionProvider pool = ConnectionProvider.builder("pdp-" + name)
                .maxConnections(settings.bulkhead())
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.timeout().toMillis());
        WebClient.Builder configured = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY);
        if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) {
            configured.baseUrl(settings.baseUrl());
        }
        return configured.build();
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
                    try {
                        return objectMapper.readValue(bytes, type);
                    } catch (IOException e) {
                        throw new ContractViolationException(type.getSimpleName() + ": unreadable body", e);
                    }
                });
    }
}
