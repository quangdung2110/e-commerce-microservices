package com.platform.promotionservice.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.service.ProductTombstoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer duy nhất của Promotion: {@code com.ecommerce.catalog.product-deleted.v1}
 * trên {@code t.product.events} (WF-PROMO-009). Message khác trên topic được ACK
 * không tạo hiệu ứng. Offset chỉ commit sau khi transaction DB đã commit; event sai
 * hợp đồng đi thẳng DLQ.
 */
@Component
@ConditionalOnProperty(prefix = "promotion.kafka", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ProductDeletedConsumer {

    public static final String PRODUCT_DELETED_TYPE = "com.ecommerce.catalog.product-deleted.v1";
    public static final String PRODUCT_SOURCE = "/services/product";

    private final ObjectMapper objectMapper;
    private final ProductTombstoneService tombstones;

    @KafkaListener(
            topics = "${promotion.kafka.product-topic}",
            groupId = "${promotion.kafka.group-id}",
            containerFactory = "productTombstoneListenerContainerFactory")
    public void onMessage(String message) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new NonRetryableEventException("Product event is not valid JSON", e);
        }
        String type = event.path("type").asText(null);
        if (!PRODUCT_DELETED_TYPE.equals(type)) {
            log.debug("Skipping event type {} on the product topic", type);
            return;
        }
        if (!PRODUCT_SOURCE.equals(event.path("source").asText(null))) {
            throw new NonRetryableEventException("product-deleted event has an unexpected source", null);
        }
        Tombstone tombstone = parse(event.path("data"));
        ProductTombstoneService.Outcome outcome = tombstones.apply(tombstone.productId(),
                tombstone.catalogRevision(), tombstone.deletedAt());
        // Không có cache nào theo product để invalidate: markdown rule không nằm trong Redis và snapshot voucher
        // (theo promotion) không đổi vì voucher definition không đổi (ADR-019). Cũng không emit ngược về Product.
        log.info("Applied product tombstone product={} catalog_revision={} invalidated={} advanced={}",
                tombstone.productId(), tombstone.catalogRevision(), outcome.invalidatedRules(),
                outcome.watermarkAdvanced());
    }

    private record Tombstone(UUID productId, long catalogRevision, Instant deletedAt) {
    }

    private Tombstone parse(JsonNode data) {
        if (data.isMissingNode() || !data.isObject()) {
            throw new NonRetryableEventException("product-deleted event has no data object", null);
        }
        try {
            long revision = data.path("catalog_revision").asLong();
            if (revision < 1) {
                throw new IllegalArgumentException("catalog_revision must be >= 1");
            }
            return new Tombstone(UUID.fromString(data.path("product_id").asText()), revision,
                    Instant.parse(data.path("deleted_at").asText()));
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("product-deleted event does not match the v1 contract", e);
        }
    }

    /** Event sai hợp đồng: retry vô ích, error handler đẩy thẳng sang DLQ. */
    public static class NonRetryableEventException extends RuntimeException {
        public NonRetryableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
