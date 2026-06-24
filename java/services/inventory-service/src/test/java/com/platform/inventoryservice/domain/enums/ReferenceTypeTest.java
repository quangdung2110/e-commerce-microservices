package com.platform.inventoryservice.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceTypeTest {

    @Test
    void affectsActualQuantity_deduct_true() {
        assertThat(ReferenceType.ORDER_DEDUCTED.affectsActualQuantity()).isTrue();
    }

    @Test
    void affectsActualQuantity_adminRestock_true() {
        assertThat(ReferenceType.ADMIN_RESTOCK.affectsActualQuantity()).isTrue();
    }

    @Test
    void affectsActualQuantity_orderReturnedRestock_true() {
        assertThat(ReferenceType.ORDER_RETURNED_RESTOCK.affectsActualQuantity()).isTrue();
    }

    @Test
    void affectsActualQuantity_reserved_false() {
        assertThat(ReferenceType.ORDER_RESERVED.affectsActualQuantity()).isFalse();
    }

    @Test
    void affectsActualQuantity_cancelled_false() {
        assertThat(ReferenceType.ORDER_CANCELLED.affectsActualQuantity()).isFalse();
    }

    @Test
    void affectsActualQuantity_expired_false() {
        assertThat(ReferenceType.RESERVATION_EXPIRED.affectsActualQuantity()).isFalse();
    }
}

class ChangeTypeTest {

    @Test
    void fromReferenceType_mappedValues_returnCorrectChangeType() {
        assertThat(ChangeType.fromReferenceType(ReferenceType.ORDER_RESERVED))
                .isEqualTo(ChangeType.ORDER_RESERVED);
        assertThat(ChangeType.fromReferenceType(ReferenceType.ORDER_DEDUCTED))
                .isEqualTo(ChangeType.ORDER_DEDUCTED);
        assertThat(ChangeType.fromReferenceType(ReferenceType.ORDER_CANCELLED))
                .isEqualTo(ChangeType.ORDER_CANCELLED);
        assertThat(ChangeType.fromReferenceType(ReferenceType.RESERVATION_EXPIRED))
                .isEqualTo(ChangeType.RESERVATION_EXPIRED);
    }

    @Test
    void fromReferenceType_adminRestock_throwsIllegalState() {
        assertThatThrownBy(() -> ChangeType.fromReferenceType(ReferenceType.ADMIN_RESTOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fromReferenceType_orderReturnedRestock_throwsIllegalState() {
        assertThatThrownBy(() -> ChangeType.fromReferenceType(ReferenceType.ORDER_RETURNED_RESTOCK))
                .isInstanceOf(IllegalStateException.class);
    }
}