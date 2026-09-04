package com.platform.productservice.domain.pricing;

import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.enums.MarkdownState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Công thức markdown và clamp giá (docs/product/domain.md — Giá markdown,
 * PRODUCT-BR-004).
 */
class MarkdownPricingTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    @Test
    @DisplayName("Không có projection thì giá công bố bằng base price")
    void noProjectionKeepsBasePrice() {
        assertThat(MarkdownPricing.sellingPrice(25_000_000L, null)).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Projection INACTIVE không giảm giá")
    void inactiveProjectionKeepsBasePrice() {
        MarkdownProjectionEntity projection = percentage(1000, null);
        projection.setState(MarkdownState.INACTIVE);

        assertThat(MarkdownPricing.sellingPrice(25_000_000L, projection)).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("PERCENTAGE làm tròn xuống")
    void percentageFloorsTheDiscount() {
        // 999 * 1234 / 10000 = 123,2766 -> discount 123
        assertThat(MarkdownPricing.sellingPrice(999L, percentage(1234, null))).isEqualTo(876L);
    }

    @Test
    @DisplayName("PERCENTAGE bị chặn bởi max_discount_vnd")
    void percentageRespectsCap() {
        // 10% của 25tr là 2,5tr nhưng cap là 2tr
        assertThat(MarkdownPricing.sellingPrice(25_000_000L, percentage(1000, 2_000_000L)))
                .isEqualTo(23_000_000L);
    }

    @Test
    @DisplayName("Cap lớn hơn mức giảm thì không có tác dụng")
    void capAboveDiscountIsIgnored() {
        assertThat(MarkdownPricing.sellingPrice(25_000_000L, percentage(1000, 9_000_000L)))
                .isEqualTo(22_500_000L);
    }

    @Test
    @DisplayName("FIXED_VND trừ đúng số tiền")
    void fixedDiscountSubtractsAmount() {
        assertThat(MarkdownPricing.sellingPrice(25_000_000L, fixed(1_500_000L)))
                .isEqualTo(23_500_000L);
    }

    @Test
    @DisplayName("Giảm giá lớn hơn base price bị clamp về 0, không âm")
    void discountIsClampedAtZero() {
        assertThat(MarkdownPricing.sellingPrice(1_000_000L, fixed(5_000_000L))).isZero();
    }

    @Test
    @DisplayName("Giảm 100% cho giá 0, vẫn nằm trong [0, base]")
    void fullPercentageDiscountGivesZero() {
        assertThat(MarkdownPricing.sellingPrice(25_000_000L, percentage(10000, null))).isZero();
    }

    @Test
    @DisplayName("Base price rất lớn không tràn số khi nhân với bps")
    void hugeBasePriceDoesNotOverflow() {
        long base = Long.MAX_VALUE / 2;
        long selling = MarkdownPricing.sellingPrice(base, percentage(10000, null));

        assertThat(selling).isZero();
    }

    @Test
    @DisplayName("Snapshot ACTIVE còn hạn được giữ ACTIVE")
    void activeSnapshotBeforeEndStaysActive() {
        Instant endsAt = NOW.plus(1, ChronoUnit.HOURS);

        assertThat(MarkdownPricing.effectiveState(MarkdownState.ACTIVE, endsAt, NOW))
                .isEqualTo(MarkdownState.ACTIVE);
    }

    @Test
    @DisplayName("Snapshot ACTIVE đến muộn sau ends_at được lưu INACTIVE")
    void lateActiveSnapshotIsStoredInactive() {
        Instant endsAt = NOW.minus(1, ChronoUnit.SECONDS);

        assertThat(MarkdownPricing.effectiveState(MarkdownState.ACTIVE, endsAt, NOW))
                .isEqualTo(MarkdownState.INACTIVE);
    }

    @Test
    @DisplayName("Snapshot INACTIVE luôn là INACTIVE")
    void inactiveSnapshotStaysInactive() {
        assertThat(MarkdownPricing.effectiveState(MarkdownState.INACTIVE, NOW.plusSeconds(3600), NOW))
                .isEqualTo(MarkdownState.INACTIVE);
    }

    private MarkdownProjectionEntity percentage(int bps, Long cap) {
        MarkdownProjectionEntity projection = base();
        projection.setDiscountType(DiscountType.PERCENTAGE);
        projection.setDiscountBps(bps);
        projection.setMaxDiscountVnd(cap);
        return projection;
    }

    private MarkdownProjectionEntity fixed(long amount) {
        MarkdownProjectionEntity projection = base();
        projection.setDiscountType(DiscountType.FIXED_VND);
        projection.setFixedDiscountVnd(amount);
        return projection;
    }

    private MarkdownProjectionEntity base() {
        MarkdownProjectionEntity projection = new MarkdownProjectionEntity();
        projection.setState(MarkdownState.ACTIVE);
        projection.setStartsAt(NOW.minus(1, ChronoUnit.HOURS));
        projection.setEndsAt(NOW.plus(1, ChronoUnit.HOURS));
        return projection;
    }
}
