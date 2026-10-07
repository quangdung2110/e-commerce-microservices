package com.platform.promotionservice.infrastructure.product;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.config.PromotionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Gọi {@code GET /internal/v1/products/{product_id}/snapshot} của Product với service
 * identity {@code promotion-service}; Product phải allowlist identity này cho nhóm
 * route snapshot. 404 → Product không tồn tại; lỗi khác → 503 retryable.
 */
@Component
@ConditionalOnProperty(prefix = "promotion.product", name = "validation-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class HttpProductCatalogClient implements ProductCatalogClient {

    private final RestClient restClient;
    private final PromotionProperties.Product product;

    public HttpProductCatalogClient(PromotionProperties properties) {
        this.product = properties.getProduct();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) product.getTimeout().toMillis());
        factory.setReadTimeout((int) product.getTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(product.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(product.getIdentityHeader(), product.getIdentity())
                .build();
    }

    @Override
    public boolean exists(UUID productId) {
        try {
            restClient.get()
                    .uri("/internal/v1/products/{id}/snapshot", productId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            log.warn("Product validation rejected by Product Service: status={}", e.getStatusCode());
            throw new DomainException(ErrorCode.PROMOTION_UNAVAILABLE, "Product validation is unavailable");
        } catch (RestClientException e) {
            log.warn("Product validation failed: {}", e.getMessage());
            throw new DomainException(ErrorCode.PROMOTION_UNAVAILABLE, "Product validation is unavailable");
        }
    }
}
