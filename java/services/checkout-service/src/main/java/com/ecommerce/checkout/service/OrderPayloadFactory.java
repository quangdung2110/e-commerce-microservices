package com.ecommerce.checkout.service;

import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.media.MediaUrls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code CheckoutOrderSubmissionRequest} from a snapshot (interfaces.md, "Mapping sang Order
 * submission"). The result is serialized once, stored encrypted at claim time and replayed byte for
 * byte on every later attempt, so every call carries the same {@code Idempotency-Key} and body.
 *
 * <p>What is intentionally absent: {@code variant_name} (Product has no variant label yet),
 * {@code province_name}/{@code district_name}/{@code ward_name} (User snapshot has none), and the
 * address ID. {@code user_ack_degraded_pricing} is always {@code false}.
 */
@Component
public class OrderPayloadFactory {

    private final ObjectMapper mapper;
    private final MediaUrls media;

    public OrderPayloadFactory(ObjectMapper mapper, MediaUrls media) {
        this.mapper = mapper;
        this.media = media;
    }

    public String build(String token, Snapshot snapshot) {
        ObjectNode root = mapper.createObjectNode();
        root.put("checkout_token", token);
        root.put("user_id", snapshot.userId());
        root.put("pricing_hash", snapshot.pricingHash());
        root.put("items_hash", snapshot.itemsHash());
        root.put("promotion_campaign_revision", snapshot.promotion().campaignRevision());
        root.put("user_ack_degraded_pricing", false);

        Snapshot.Address address = snapshot.address();
        ObjectNode delivery = root.putObject("delivery_address_snapshot");
        delivery.put("recipient_name", address.recipientName());
        delivery.put("recipient_phone", address.recipientPhone());
        delivery.put("address_line", address.addressLine());
        delivery.put("province_code", address.provinceCode());
        delivery.put("district_code", address.districtCode());
        delivery.put("ward_code", address.wardCode());

        ArrayNode items = root.putArray("items");
        for (Snapshot.Item item : snapshot.items()) {
            ObjectNode node = items.addObject();
            node.put("line_reference", item.lineReference());
            node.put("product_id", item.productId());
            node.put("sku_id", item.skuId());
            node.put("quantity", item.quantity());
            node.set("unit_price", money(item.unitPrice()));
            ArrayNode categories = node.putArray("category_ids");
            item.categoryIds().forEach(categories::add);
            node.put("snapshot_name", item.productName());
            node.put("snapshot_image_url", media.orderUrl(item.imageStorageKey()));
            ObjectNode attributes = node.putObject("snapshot_attributes");
            item.variantAttributes().forEach(attributes::put);
        }

        ObjectNode pricing = root.putObject("pricing");
        pricing.set("total_amount", money(snapshot.totalAmount()));
        pricing.set("total_discount", money(snapshot.totalDiscount()));
        pricing.set("final_amount", money(snapshot.finalAmount()));

        ArrayNode promotions = root.putArray("promotions");
        if (snapshot.hasPromotion()) {
            ObjectNode promotion = promotions.addObject();
            promotion.put("promotion_id", snapshot.promotion().promotionId());
            promotion.set("discount_amount", money(snapshot.promotion().discount()));
        }

        ObjectNode plan = root.putObject("allocation_plan");
        plan.put("allocation_plan_id", snapshot.allocation().allocationPlanId());
        ArrayNode lines = plan.putArray("lines");
        for (Snapshot.AllocationLine line : snapshot.allocation().lines()) {
            lines.addObject()
                    .put("line_reference", line.lineReference())
                    .put("sku_id", line.skuId())
                    .put("warehouse_id", line.warehouseId())
                    .put("quantity", line.quantity());
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the Order payload", e);
        }
    }

    private ObjectNode money(long amount) {
        return mapper.createObjectNode().put("amount_minor", amount).put("currency", "VND");
    }
}
