package com.platform.promotionservice.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.InactiveReason;
import com.platform.promotionservice.domain.enums.MarkdownState;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Dựng CloudEvents 1.0 structured JSON cho
 * {@code com.ecommerce.promotion.product-markdown-snapshot.v1}
 * (contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json).
 * ACTIVE mang full rule/discount/interval; INACTIVE chỉ mang reason.
 */
@Component
@RequiredArgsConstructor
public class MarkdownSnapshotEvents {

    public static final String EVENT_TYPE = "com.ecommerce.promotion.product-markdown-snapshot.v1";
    public static final String SOURCE = "/services/promotion";

    private final ObjectMapper objectMapper;
    private final PromotionProperties properties;

    public record Snapshot(UUID productId, long sourceRevision, MarkdownState state,
                           MarkdownRuleEntity winner, Instant startsAt, Instant endsAt,
                           InactiveReason inactiveReason) {

        public static Snapshot active(UUID productId, long sourceRevision, MarkdownRuleEntity winner,
                                      Instant startsAt, Instant endsAt) {
            return new Snapshot(productId, sourceRevision, MarkdownState.ACTIVE, winner, startsAt, endsAt, null);
        }

        public static Snapshot inactive(UUID productId, long sourceRevision, InactiveReason reason) {
            return new Snapshot(productId, sourceRevision, MarkdownState.INACTIVE, null, null, null, reason);
        }
    }

    /** Causal dedup key: product + campaign + campaign revision + trigger; state không thuộc key. */
    public static String dedupKey(UUID productId, long campaignId, long campaignRevision, ScheduleTrigger trigger) {
        return "markdown:" + productId + ":campaign:" + campaignId + ":rev:" + campaignRevision
                + ":trigger:" + trigger.name();
    }

    public String toCloudEventJson(UUID eventId, Snapshot snapshot, Instant time) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("specversion", "1.0");
        event.put("id", eventId.toString());
        event.put("type", EVENT_TYPE);
        event.put("source", SOURCE);
        event.put("subject", "products/" + snapshot.productId());
        event.put("time", time.toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", properties.getMarkdown().getDataschema());
        event.put("sequence", snapshot.sourceRevision());
        String correlation = CallerContext.currentRequestId();
        if (correlation != null && !correlation.isBlank()) {
            event.put("correlationid", correlation);
        }
        String traceparent = CallerContext.currentTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            event.put("traceparent", traceparent);
        }

        ObjectNode data = event.putObject("data");
        data.put("product_id", snapshot.productId().toString());
        data.put("source_revision", snapshot.sourceRevision());
        data.put("state", snapshot.state().name());
        if (snapshot.state() == MarkdownState.ACTIVE) {
            MarkdownRuleEntity winner = snapshot.winner();
            data.put("promotion_rule_id", winner.getId());
            ObjectNode discount = data.putObject("discount");
            discount.put("type", winner.getDiscountType().name());
            if (winner.getDiscountType() == DiscountType.PERCENTAGE) {
                discount.put("discount_bps", winner.getDiscountValue());
                if (winner.getMaxDiscountVnd() != null) {
                    discount.put("max_discount_vnd", winner.getMaxDiscountVnd());
                } else {
                    discount.putNull("max_discount_vnd");
                }
            } else {
                discount.put("fixed_discount_vnd", winner.getDiscountValue());
            }
            data.put("starts_at", snapshot.startsAt().toString());
            data.put("ends_at", snapshot.endsAt().toString());
        } else {
            data.put("inactive_reason", snapshot.inactiveReason().name());
        }
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize markdown snapshot event", e);
        }
    }
}
