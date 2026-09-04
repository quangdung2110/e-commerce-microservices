package com.platform.productservice.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.enums.MarkdownState;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.infrastructure.event.EventContext;
import com.platform.productservice.service.MarkdownApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer duy nhất của Product: markdown snapshot Product-scope do Promotion
 * phát (docs/product/interfaces.md — Events Product tiêu thụ).
 *
 * <p>Product không consume {@code INVENTORY_STOCK_CHANGED}, không consume event
 * của chính mình và không có scheduled job. Snapshot là state đầy đủ nên
 * idempotent qua {@code source_revision}; không cần inbox table.
 *
 * <p>Lỗi tạm thời được ném ra để error handler retry rồi đẩy DLQ; offset chỉ
 * commit sau khi transaction DB đã commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarkdownSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final MarkdownApplyService markdownApplyService;

    @KafkaListener(
            topics = "${product.markdown.topic}",
            groupId = "${product.markdown.group-id}",
            containerFactory = "markdownListenerContainerFactory")
    public void onMessage(String message) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (Exception e) {
            // Payload không phải JSON: retry không giúp được gì, đẩy thẳng DLQ.
            throw new NonRetryableEventException("Markdown snapshot is not valid JSON", e);
        }

        String type = event.path("type").asText(null);
        if (!CatalogEventTypes.MARKDOWN_SNAPSHOT.equals(type)) {
            log.debug("Skipping event type {} on the markdown topic", type);
            return;
        }

        try {
            EventContext.set(event.path("traceparent").asText(null),
                    event.path("correlationid").asText(null));
            MarkdownApplyService.Snapshot snapshot = parse(event.path("data"));
            MarkdownApplyService.Outcome outcome = markdownApplyService.apply(snapshot);
            log.info("Markdown snapshot product={} source_revision={} outcome={}",
                    snapshot.productId(), snapshot.sourceRevision(), outcome);
        } finally {
            EventContext.clear();
        }
    }

    private MarkdownApplyService.Snapshot parse(JsonNode data) {
        if (data.isMissingNode() || !data.isObject()) {
            throw new NonRetryableEventException("Markdown snapshot has no data object", null);
        }
        try {
            JsonNode discount = data.path("discount");
            DiscountType discountType = DiscountType.valueOf(discount.path("type").asText());
            return new MarkdownApplyService.Snapshot(
                    UUID.fromString(data.path("product_id").asText()),
                    data.path("promotion_rule_id").asLong(),
                    data.path("source_revision").asLong(),
                    MarkdownState.valueOf(data.path("state").asText()),
                    discountType,
                    discount.hasNonNull("discount_bps") ? discount.get("discount_bps").asInt() : null,
                    discount.hasNonNull("fixed_discount_vnd") ? discount.get("fixed_discount_vnd").asLong() : null,
                    discount.hasNonNull("max_discount_vnd") ? discount.get("max_discount_vnd").asLong() : null,
                    Instant.parse(data.path("starts_at").asText()),
                    Instant.parse(data.path("ends_at").asText()));
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Markdown snapshot does not match the v1 contract", e);
        }
    }

    /** Event sai hợp đồng: retry vô ích, error handler đẩy thẳng sang DLQ. */
    public static class NonRetryableEventException extends RuntimeException {

        public NonRetryableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
