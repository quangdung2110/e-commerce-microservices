package com.ecommerce.order.domain;

import com.ecommerce.order.common.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRulesTest {

    @Test
    void discountSharesSumExactlyAndNeverExceedGross() {
        Random random = new Random(42);
        for (int round = 0; round < 2_000; round++) {
            int count = 1 + random.nextInt(8);
            List<DiscountAllocator.Line> lines = new ArrayList<>();
            long gross = 0;
            for (int i = 0; i < count; i++) {
                long g = 1 + random.nextInt(5_000_000);
                lines.add(new DiscountAllocator.Line(100 + i, g));
                gross += g;
            }
            long discount = (long) (random.nextDouble() * gross);
            Map<Long, Long> shares = DiscountAllocator.allocate(lines, discount);
            assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(discount);
            for (DiscountAllocator.Line line : lines) {
                assertThat(shares.get(line.key())).isBetween(0L, line.grossMinor());
            }
        }
    }

    @Test
    void largestRemainderTieBreaksByStableLineId() {
        // 100 split across three equal lines: one unit of remainder goes to the lowest ID.
        Map<Long, Long> shares = DiscountAllocator.allocate(List.of(new DiscountAllocator.Line(3, 1000),
                new DiscountAllocator.Line(1, 1000), new DiscountAllocator.Line(2, 1000)), 100);
        assertThat(shares).containsEntry(1L, 34L).containsEntry(2L, 33L).containsEntry(3L, 33L);
    }

    @Test
    void selectedSubsetRefundNeverExceedsFinalAmount() {
        // A×2 @300, B×1 @1000, C×3 @100; 200 discount.
        Map<Long, Long> shares = DiscountAllocator.allocate(List.of(new DiscountAllocator.Line(1, 600),
                new DiscountAllocator.Line(2, 1000), new DiscountAllocator.Line(3, 300)), 200);
        long netA = 600 - shares.get(1L);
        long netC = 300 - shares.get(3L);
        assertThat(netA + netC).isLessThanOrEqualTo(1900 - 200);
        assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(200);
    }

    @Test
    void discountLargerThanGrossIsRejected() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(List.of(new DiscountAllocator.Line(1, 10)), 11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizerFoldsVietnameseAccentsAndD() {
        assertThat(NameNormalizer.normalize("Áo Thun ĐẸP Đồng Hồ")).isEqualTo("ao thun dep dong ho");
        assertThat(NameNormalizer.normalize("Bình nước")).isEqualTo("binh nuoc");
        assertThat(NameNormalizer.normalize("ＡＢＣ")).isEqualTo("abc");
    }

    @Test
    void buyerTabPrecedenceAndUiStatus() {
        assertThat(BuyerTab.derive(OrderStatus.CANCELLING, true)).isEqualTo(BuyerTab.TO_PAY);
        assertThat(BuyerTab.derive(OrderStatus.PROCESSING, false)).isEqualTo(BuyerTab.TO_SHIP);
        assertThat(BuyerTab.derive(OrderStatus.PAID, true)).isEqualTo(BuyerTab.TO_RECEIVE);
        assertThat(BuyerTab.derive(OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED, false)).isEqualTo(BuyerTab.RETURN_REFUND);
        assertThat(BuyerTab.uiPrimaryStatus(OrderStatus.PROCESSING, BuyerTab.TO_RECEIVE)).isEqualTo("IN_DELIVERY");
        assertThat(BuyerTab.uiPrimaryStatus(OrderStatus.PAID, BuyerTab.TO_SHIP)).isEqualTo("PREPARING");
        assertThat(BuyerTab.uiPrimaryStatus(OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED, BuyerTab.RETURN_REFUND))
                .isEqualTo("UNDER_REVIEW");
        assertThat(BuyerTab.TO_PAY.rank()).isEqualTo(10);
        assertThat(BuyerTab.CANCELLED.rank()).isEqualTo(60);
    }

    @Test
    void itemStatusIsDerivedFromLines() {
        assertThat(FulfillmentStatus.deriveItemStatus(List.of(FulfillmentStatus.PROCESSING, FulfillmentStatus.DISPATCHED)))
                .isEqualTo("PARTIALLY_DISPATCHED");
        assertThat(FulfillmentStatus.deriveItemStatus(List.of(FulfillmentStatus.DISPATCHED, FulfillmentStatus.DISPATCHED)))
                .isEqualTo("DISPATCHED");
        assertThat(FulfillmentStatus.deriveItemStatus(List.of(FulfillmentStatus.DELIVERED, FulfillmentStatus.DISPATCHED)))
                .isEqualTo("PARTIALLY_DELIVERED");
        assertThat(FulfillmentStatus.deriveItemStatus(List.of(FulfillmentStatus.DELIVERED))).isEqualTo("DELIVERED");
    }

    @Test
    void cancelReasonMappingsFollowAdr020() {
        assertThat(CancelReason.USER_CANCEL.paymentCancelReason()).isEqualTo("BUYER_CANCELLED");
        assertThat(CancelReason.USER_CANCEL.inventoryReleaseReason()).isEqualTo("BUYER_CANCELLED");
        assertThat(CancelReason.PAYMENT_TIMEOUT.inventoryReleaseReason()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(CancelReason.PAYMENT_FAILED.paymentCancelReason()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(CancelReason.ADMIN_FORCE_CANCEL.paymentCancelReason()).isEqualTo("ORDER_CANCELLED_PRE_CAPTURE");
        assertThat(CancelReason.PRE_PAYMENT_SAGA_FAILED.inventoryReleaseReason()).isEqualTo("PRE_PAYMENT_SAGA_FAILED");
        assertThat(CancelReason.forDeadline("FAILED")).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(CancelReason.forDeadline(null)).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
        assertThat(CancelReason.forTerminalNonCapture("EXPIRED", null)).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
        assertThat(CancelReason.forTerminalNonCapture("CANCELED", "PROVIDER_CANCELED")).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(CancelReason.PRE_PAYMENT_SAGA_FAILED.notificationDisplayReason(CancelReason.DETAIL_INVENTORY_REJECTED))
                .isEqualTo("out_of_stock");
        assertThat(CancelReason.PRE_PAYMENT_SAGA_FAILED.notificationDisplayReason("PAYMENT_INITIATION_REJECTED"))
                .isEqualTo("checkout_failed");
    }

    @Test
    void canonicalJsonSortsMembersAndRejectsFractions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String canonical = CanonicalJson.canonicalize(mapper.readTree("{\"b\":1,\"a\":[true,null,\"x\\n\"]}"));
        assertThat(canonical).isEqualTo("{\"a\":[true,null,\"x\\n\"],\"b\":1}");
        assertThatThrownBy(() -> CanonicalJson.canonicalize(mapper.readTree("{\"a\":1.5}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
