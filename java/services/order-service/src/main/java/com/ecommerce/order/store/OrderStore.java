package com.ecommerce.order.store;

import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.PaymentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Master order, delivery units, lines and promotion snapshots (data.md §3.1-3.4). */
@Repository
public class OrderStore {

    /** Columns an FSM transition may write through {@link #update}. */
    private static final Set<String> UPDATABLE = Set.of(
            "status", "payment_status", "saga_phase", "active_payment_attempt_number",
            "active_payment_transaction_id", "captured_payment_transaction_id",
            "inventory_reservation_expires_at", "payment_deadline_at", "payment_deadline_locked_at",
            "cancel_reason", "cancel_reason_detail", "return_reason", "buyer_return_locked_at",
            "last_notification_sequence_id", "last_event_sequence_id", "paid_at", "completed_at",
            "cancelled_at", "refunded_at");

    public record OrderRow(
            long id, String orderCode, UUID userId, String checkoutToken, String pricingHash, String itemsHash,
            long promotionCampaignRevision, long totalAmountMinor, long totalDiscountMinor, long finalAmountMinor,
            String allocationPlanId, OrderStatus status, PaymentStatus paymentStatus, String sagaPhase,
            Integer activeAttemptNumber, String activeTransactionId, String capturedTransactionId,
            Instant inventoryReservationExpiresAt, Instant paymentDeadlineAt, Instant paymentDeadlineLockedAt,
            String cancelReason, String cancelReasonDetail, String returnReason, Instant buyerReturnLockedAt,
            long lastNotificationSequenceId, long lastEventSequenceId, Instant paidAt, Instant completedAt,
            Instant cancelledAt, Instant refundedAt, Instant createdAt, Instant updatedAt) {
    }

    public record ItemRow(long id, long orderId, String warehouseId, String status, String deliveryAddressJson) {
    }

    public record LineRow(
            long id, long orderId, long orderItemId, String allocationPlanId, String lineReference,
            String warehouseId, String skuId, String productId, int quantity, long unitPriceMinor,
            long discountShareMinor, long netAmountMinor, FulfillmentStatus fulfillmentStatus,
            UUID dispatchEventId, Instant dispatchedAt, String deliveryReference, UUID deliveryConfirmedBy,
            Instant deliveredAt, String snapshotName, String snapshotVariantName, String snapshotImageUrl,
            String snapshotAttributesJson, String snapshotCategoryIdsJson) {
    }

    public record PromotionRow(long id, long orderId, String promotionId, long discountAmountMinor,
                               Instant reservedAt, Instant reservationExpiresAt) {
    }

    public record NewOrder(long id, UUID userId, String checkoutToken, String pricingHash, String itemsHash,
                           long promotionCampaignRevision, long totalAmountMinor, long totalDiscountMinor,
                           long finalAmountMinor, String allocationPlanId, Instant paymentDeadlineAt,
                           Instant createdAt) {
    }

    public record NewLine(long orderItemId, String allocationPlanId, String lineReference, String warehouseId,
                          String skuId, String productId, int quantity, long unitPriceMinor,
                          long discountShareMinor, String snapshotName, String snapshotVariantName,
                          String snapshotImageUrl, String snapshotNameSearch, String snapshotAttributesJson,
                          String snapshotCategoryIdsJson) {
    }

    private static final RowMapper<OrderRow> ORDER = (rs, i) -> new OrderRow(
            rs.getLong("id"), rs.getString("order_code"), Db.uuid(rs, "user_id"), rs.getString("checkout_token"),
            rs.getString("pricing_hash"), rs.getString("items_hash"), rs.getLong("promotion_campaign_revision"),
            rs.getLong("total_amount_minor"), rs.getLong("total_discount_minor"), rs.getLong("final_amount_minor"),
            rs.getString("inventory_allocation_plan_id"), OrderStatus.valueOf(rs.getString("status")),
            PaymentStatus.valueOf(rs.getString("payment_status")), rs.getString("saga_phase"),
            Db.nullableInt(rs, "active_payment_attempt_number"), rs.getString("active_payment_transaction_id"),
            rs.getString("captured_payment_transaction_id"), Db.instant(rs, "inventory_reservation_expires_at"),
            Db.instant(rs, "payment_deadline_at"), Db.instant(rs, "payment_deadline_locked_at"),
            rs.getString("cancel_reason"), rs.getString("cancel_reason_detail"), rs.getString("return_reason"),
            Db.instant(rs, "buyer_return_locked_at"), rs.getLong("last_notification_sequence_id"),
            rs.getLong("last_event_sequence_id"), Db.instant(rs, "paid_at"), Db.instant(rs, "completed_at"),
            Db.instant(rs, "cancelled_at"), Db.instant(rs, "refunded_at"), Db.instant(rs, "created_at"),
            Db.instant(rs, "updated_at"));

    private static final RowMapper<ItemRow> ITEM = (rs, i) -> new ItemRow(rs.getLong("id"), rs.getLong("order_id"),
            rs.getString("warehouse_id"), rs.getString("status"), rs.getString("delivery_address_snapshot"));

    private static final RowMapper<LineRow> LINE = (rs, i) -> new LineRow(
            rs.getLong("id"), rs.getLong("order_id"), rs.getLong("order_item_id"),
            rs.getString("inventory_allocation_plan_id"), rs.getString("allocation_plan_line_reference"),
            rs.getString("allocated_warehouse_id"), rs.getString("sku_id"), rs.getString("product_id"),
            rs.getInt("quantity"), rs.getLong("unit_price_minor"), rs.getLong("discount_share_minor"),
            rs.getLong("net_amount_minor"), FulfillmentStatus.valueOf(rs.getString("fulfillment_status")),
            Db.uuid(rs, "dispatch_event_id"), Db.instant(rs, "dispatched_at"), rs.getString("delivery_reference"),
            Db.uuid(rs, "delivery_confirmed_by"), Db.instant(rs, "delivered_at"), rs.getString("snapshot_name"),
            rs.getString("snapshot_variant_name"), rs.getString("snapshot_image_url"),
            rs.getString("snapshot_attributes"), rs.getString("snapshot_category_ids"));

    private static final RowMapper<PromotionRow> PROMOTION = (rs, i) -> new PromotionRow(rs.getLong("id"),
            rs.getLong("order_id"), rs.getString("promotion_id"), rs.getLong("discount_amount_minor"),
            Db.instant(rs, "reserved_at"), Db.instant(rs, "reservation_expires_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public OrderStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextOrderId() {
        Long id = jdbc.getJdbcTemplate().queryForObject("SELECT nextval('orders_id_seq')", Long.class);
        if (id == null) {
            throw new IllegalStateException("orders_id_seq returned null");
        }
        return id;
    }

    public void insert(NewOrder order) {
        jdbc.update("""
                INSERT INTO orders (id, order_code, user_id, checkout_token, pricing_hash, items_hash,
                    promotion_campaign_revision, total_amount_minor, total_discount_minor, final_amount_minor,
                    inventory_allocation_plan_id, status, payment_status, saga_phase, payment_deadline_at,
                    created_at, updated_at)
                VALUES (:id, :code, :user, :token, :pricing, :items, :rev, :total, :discount, :final, :plan,
                    'PENDING_PAYMENT', 'PENDING', 'PRE_PAYMENT', :deadline, :created, :created)
                """, new MapSqlParameterSource()
                .addValue("id", order.id()).addValue("code", "ORD-" + order.id())
                .addValue("user", order.userId()).addValue("token", order.checkoutToken())
                .addValue("pricing", order.pricingHash()).addValue("items", order.itemsHash())
                .addValue("rev", order.promotionCampaignRevision()).addValue("total", order.totalAmountMinor())
                .addValue("discount", order.totalDiscountMinor()).addValue("final", order.finalAmountMinor())
                .addValue("plan", order.allocationPlanId()).addValue("deadline", Db.ts(order.paymentDeadlineAt()))
                .addValue("created", Db.ts(order.createdAt())));
    }

    public long insertItem(long orderId, String warehouseId, String addressJson) {
        Long id = jdbc.queryForObject("""
                INSERT INTO order_items (order_id, warehouse_id, status, delivery_address_snapshot)
                VALUES (:order, :warehouse, 'PROCESSING', CAST(:address AS jsonb)) RETURNING id
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("warehouse", warehouseId)
                .addValue("address", addressJson), Long.class);
        return id == null ? 0 : id;
    }

    public long insertLine(long orderId, NewLine line) {
        long gross = Math.multiplyExact(line.unitPriceMinor(), (long) line.quantity());
        Long id = jdbc.queryForObject("""
                INSERT INTO order_item_lines (order_id, order_item_id, inventory_allocation_plan_id,
                    allocation_plan_line_reference, allocated_warehouse_id, sku_id, product_id, quantity,
                    unit_price_minor, discount_share_minor, net_amount_minor, fulfillment_status, snapshot_name,
                    snapshot_variant_name, snapshot_image_url, snapshot_name_search, snapshot_attributes,
                    snapshot_category_ids)
                VALUES (:order, :item, :plan, :ref, :warehouse, :sku, :product, :quantity, :price, 0, :gross,
                    'PROCESSING', :name, :variant, :image, :search, CAST(:attributes AS jsonb),
                    CAST(:categories AS jsonb))
                RETURNING id
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("item", line.orderItemId())
                .addValue("plan", line.allocationPlanId()).addValue("ref", line.lineReference())
                .addValue("warehouse", line.warehouseId()).addValue("sku", line.skuId())
                .addValue("product", line.productId()).addValue("quantity", line.quantity())
                .addValue("price", line.unitPriceMinor()).addValue("gross", gross)
                .addValue("name", line.snapshotName()).addValue("variant", line.snapshotVariantName())
                .addValue("image", line.snapshotImageUrl()).addValue("search", line.snapshotNameSearch())
                .addValue("attributes", line.snapshotAttributesJson())
                .addValue("categories", line.snapshotCategoryIdsJson()), Long.class);
        return id == null ? 0 : id;
    }

    /** Applies the checkout-time discount share; net = gross - share (ADR-017). */
    public void setDiscountShare(long lineId, long shareMinor) {
        jdbc.update("""
                UPDATE order_item_lines SET discount_share_minor = :share,
                    net_amount_minor = quantity::BIGINT * unit_price_minor - :share
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", lineId).addValue("share", shareMinor));
    }

    public void insertPromotion(long orderId, String promotionId, long discountMinor) {
        jdbc.update("""
                INSERT INTO order_promotions (order_id, promotion_id, discount_amount_minor)
                VALUES (:order, :promotion, :discount)
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("promotion", promotionId)
                .addValue("discount", discountMinor));
    }

    public void recordPromotionReservation(long orderId, String promotionId, Instant reservedAt, Instant expiresAt) {
        jdbc.update("""
                UPDATE order_promotions SET reserved_at = :reserved, reservation_expires_at = :expires
                WHERE order_id = :order AND promotion_id = :promotion
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("promotion", promotionId)
                .addValue("reserved", Db.ts(reservedAt)).addValue("expires", Db.ts(expiresAt)));
    }

    public Optional<OrderRow> find(long orderId) {
        return jdbc.query("SELECT * FROM orders WHERE id = :id", Map.of("id", orderId), ORDER).stream().findFirst();
    }

    public Optional<OrderRow> lock(long orderId) {
        return jdbc.query("SELECT * FROM orders WHERE id = :id FOR UPDATE", Map.of("id", orderId), ORDER)
                .stream().findFirst();
    }

    public Optional<OrderRow> findByCheckoutToken(String token) {
        return jdbc.query("SELECT * FROM orders WHERE checkout_token = :token", Map.of("token", token), ORDER)
                .stream().findFirst();
    }

    /** Writes whitelisted columns plus updated_at; callers hold the order row lock. */
    public void update(long orderId, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("id", orderId);
        Map<String, Object> ordered = new LinkedHashMap<>(changes);
        String set = ordered.entrySet().stream().map(e -> {
            if (!UPDATABLE.contains(e.getKey())) {
                throw new IllegalArgumentException("Column not updatable: " + e.getKey());
            }
            Object value = e.getValue();
            if (value instanceof Instant instant) {
                value = Db.ts(instant);
            } else if (value instanceof Enum<?> en) {
                value = en.name();
            }
            params.addValue(e.getKey(), value);
            return e.getKey() + " = :" + e.getKey();
        }).collect(Collectors.joining(", "));
        jdbc.update("UPDATE orders SET " + set + ", updated_at = NOW() WHERE id = :id", params);
    }

    public List<ItemRow> items(long orderId) {
        return jdbc.query("SELECT * FROM order_items WHERE order_id = :order ORDER BY id", Map.of("order", orderId), ITEM);
    }

    public List<LineRow> lines(long orderId) {
        return jdbc.query("SELECT * FROM order_item_lines WHERE order_id = :order ORDER BY order_item_id, id",
                Map.of("order", orderId), LINE);
    }

    public List<LineRow> lockLines(long orderId) {
        return jdbc.query("SELECT * FROM order_item_lines WHERE order_id = :order ORDER BY id FOR UPDATE",
                Map.of("order", orderId), LINE);
    }

    public Optional<LineRow> line(long orderId, long lineId) {
        return jdbc.query("SELECT * FROM order_item_lines WHERE order_id = :order AND id = :id",
                Map.of("order", orderId, "id", lineId), LINE).stream().findFirst();
    }

    public List<PromotionRow> promotions(long orderId) {
        return jdbc.query("SELECT * FROM order_promotions WHERE order_id = :order ORDER BY id",
                Map.of("order", orderId), PROMOTION);
    }

    /** CAS PROCESSING -> DISPATCHED for exactly one line (I1). */
    public boolean markDispatched(long lineId, UUID eventId, long reservationRevision, Instant dispatchedAt) {
        return jdbc.update("""
                UPDATE order_item_lines SET fulfillment_status = 'DISPATCHED', dispatch_event_id = :event,
                    dispatch_reservation_revision = :revision, dispatched_at = :at, updated_at = NOW()
                WHERE id = :id AND fulfillment_status = 'PROCESSING'
                """, new MapSqlParameterSource().addValue("id", lineId).addValue("event", eventId)
                .addValue("revision", reservationRevision).addValue("at", Db.ts(dispatchedAt))) == 1;
    }

    /** CAS DISPATCHED -> DELIVERED for exactly one line with its Admin confirmation record (I2, ADR-021). */
    public boolean markDelivered(long lineId, String reference, UUID confirmedBy, Instant deliveredAt) {
        return jdbc.update("""
                UPDATE order_item_lines SET fulfillment_status = 'DELIVERED', delivery_reference = :reference,
                    delivery_confirmed_by = :by, delivered_at = :at, updated_at = NOW()
                WHERE id = :id AND fulfillment_status = 'DISPATCHED'
                """, new MapSqlParameterSource().addValue("id", lineId).addValue("reference", reference)
                .addValue("by", confirmedBy).addValue("at", Db.ts(deliveredAt))) == 1;
    }

    /** Lines dispatched at least {@code age} ago that still await the Admin delivery confirmation. */
    public long countDispatchedUnconfirmed(java.time.Duration age) {
        Long count = jdbc.getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM order_item_lines
                WHERE fulfillment_status = 'DISPATCHED' AND dispatched_at < NOW() - (? * INTERVAL '1 second')
                """, Long.class, age.toSeconds());
        return count == null ? 0 : count;
    }

    /** Recomputes the derived delivery-unit status from its lines. */
    public void refreshItemStatus(long orderItemId) {
        List<FulfillmentStatus> statuses = jdbc.query(
                "SELECT fulfillment_status FROM order_item_lines WHERE order_item_id = :item",
                Map.of("item", orderItemId), (rs, i) -> FulfillmentStatus.valueOf(rs.getString(1)));
        jdbc.update("UPDATE order_items SET status = :status, updated_at = NOW() WHERE id = :id",
                Map.of("status", FulfillmentStatus.deriveItemStatus(statuses), "id", orderItemId));
    }

    public static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
