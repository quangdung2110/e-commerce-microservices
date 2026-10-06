package com.ecommerce.review.integration;

import com.ecommerce.review.domain.EligibilityStatus;
import com.ecommerce.review.messaging.OrderCompletedPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WF-REVIEW-001: entitlements granted from {@code ORDER_COMPLETED}. */
class EntitlementIT extends AbstractReviewIT {

    private final UUID buyer = UUID.randomUUID();

    /**
     * {@code order_id} is an opaque string. A numeric-looking id, a UUID-shaped id and a
     * prefixed id all round-trip unchanged: Review compares them and nothing else, so
     * Order changing its internal format cannot break this.
     */
    @Test
    void anyOpaqueOrderIdRoundTripsUnparsed() {
        List<String> orderIds = List.of("10001", "order-10001",
                "3f2504e0-4f89-41d3-9a0c-0305e82c3301", "ORD:2026.09/1");

        for (String orderId : orderIds) {
            grantEntitlement(orderId, "p-1", "sku-1", buyer);
        }

        assertThat(jdbc.sql("SELECT order_id FROM review_eligibilities ORDER BY created_at")
                .query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(orderIds);
    }

    @Test
    void oneSkuOfOneOrderCreatesOneEntitlement() {
        UUID first = grantEntitlement("order-1", "p-1", "sku-1", buyer);

        entitlements.apply(UUID.randomUUID(), payload("order-1", buyer, Instant.now(),
                        new OrderCompletedPayload.Item("p-1", "sku-1", "Đen / L", null, null, 1)),
                properties.topics().order(), 0, nextOffset());

        assertThat(jdbc.sql("SELECT count(*) FROM review_eligibilities").query(Long.class).single())
                .isEqualTo(1);
        assertThat(eligibilityStatus(first)).isEqualTo(EligibilityStatus.PENDING);
    }

    /** REVIEW-BR-001: a redelivered event is a receipt no-op. */
    @Test
    void aReplayedOrderEventChangesNothing() {
        UUID eventId = UUID.randomUUID();
        long offset = nextOffset();
        OrderCompletedPayload payload = payload("order-2", buyer, Instant.now(),
                new OrderCompletedPayload.Item("p-1", "sku-1", "Đen / L", null, null, 1));

        entitlements.apply(eventId, payload, properties.topics().order(), 0, offset);
        entitlements.apply(eventId, payload, properties.topics().order(), 0, offset);

        assertThat(jdbc.sql("SELECT count(*) FROM review_eligibilities").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM review_event_receipts WHERE consumer_name = 'order-sync'")
                .query(Long.class).single()).isEqualTo(1);
    }

    /**
     * A producer that repeats a {@code (product_id, sku_id)} pair must not be able to make
     * the snapshotted variant name depend on statement ordering: the first occurrence in
     * array order wins.
     */
    @Test
    void aRepeatedProductSkuPairKeepsTheFirstVariantName() {
        entitlements.apply(UUID.randomUUID(), payload("order-3", buyer, Instant.now(),
                        new OrderCompletedPayload.Item("p-1", "sku-1", "Đen / L", null, null, 1),
                        new OrderCompletedPayload.Item("p-1", "sku-1", "Trắng / M", null, null, 2)),
                properties.topics().order(), 0, nextOffset());

        assertThat(jdbc.sql("SELECT variant_name_snapshot FROM review_eligibilities")
                .query(String.class).list())
                .containsExactly("Đen / L");
    }

    /**
     * REVIEW-BR-002 and REVIEW-INV-011: the window is measured from the business
     * timestamp. A late event records {@code EXPIRED} with the moment the window really
     * closed, which can be earlier than {@code created_at}.
     */
    @Test
    void aLateOrderEventPersistsExpiredWithItsBusinessExpiry() {
        // Truncated to microseconds up front: PostgreSQL stores timestamptz at that
        // precision and rounds, so a nanosecond-precision fixture would compare against a
        // value the database legitimately rounded rather than the one it was given.
        Instant completedAt = Instant.now().minus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        UUID eligibilityId = grantEntitlement("order-4", "p-1", "sku-1", buyer, completedAt, null);

        assertThat(eligibilityStatus(eligibilityId)).isEqualTo(EligibilityStatus.EXPIRED);

        var row = jdbc.sql("""
                SELECT completed_at, expires_at, created_at FROM review_eligibilities WHERE id = ?
                """).param(eligibilityId)
                .query((rs, n) -> List.of(
                        rs.getObject("completed_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .single();

        assertThat(row.get(0)).isEqualTo(completedAt);
        assertThat(row.get(1)).isEqualTo(completedAt.plus(days(properties.windowDays())));
        // The window closed before Review ever saw the order.
        assertThat(row.get(1)).isBefore(row.get(2));
    }

    @Test
    void aVariantNameIsOptionalForHistoricOrders() {
        UUID eligibilityId = grantEntitlement("order-5", "p-1", "sku-1", buyer, Instant.now(), null);

        assertThat(jdbc.sql("SELECT variant_name_snapshot FROM review_eligibilities WHERE id = ?")
                .param(eligibilityId).query(String.class).optional()).isEmpty();
    }

    /** The buyer's screens show what was bought without calling Order or Product. */
    @Test
    void theProductNameAndImageAreSnapshottedWithTheEntitlement() {
        UUID eligibilityId = grantEntitlement("order-7", "p-1", "sku-1", buyer, Instant.now(), "Đen / L",
                "Áo thun Terra", "http://localhost:8088/media/p-1/main.jpg");

        var row = jdbc.sql("""
                SELECT product_name_snapshot, product_image_url_snapshot
                  FROM review_eligibilities WHERE id = ?
                """).param(eligibilityId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2))).single();

        assertThat(row).containsExactly("Áo thun Terra", "http://localhost:8088/media/p-1/main.jpg");
    }

    @Test
    void theProductSnapshotIsOptionalForHistoricOrders() {
        UUID eligibilityId = grantEntitlement("order-8", "p-1", "sku-1", buyer, Instant.now(), "Đen / L");

        var row = jdbc.sql("""
                SELECT product_name_snapshot, product_image_url_snapshot
                  FROM review_eligibilities WHERE id = ?
                """).param(eligibilityId)
                .query((rs, n) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2))).single();

        assertThat(row).containsExactly(null, null);
    }

    /** No event leaves Review when an entitlement is granted. */
    @Test
    void grantingAnEntitlementPublishesNothing() {
        grantEntitlement("order-6", "p-1", "sku-1", buyer);

        assertThat(outboxTypes()).isEmpty();
    }

    private static OrderCompletedPayload payload(String orderId, UUID buyer, Instant completedAt,
                                                 OrderCompletedPayload.Item... items) {
        return new OrderCompletedPayload(orderId, buyer, completedAt, List.of(items));
    }
}
