package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionTest {

    @Test
    void refundScopeMismatchException_storesExistingAndRequested() {
        List<Long> existing = List.of(2001L, 2002L);
        List<Long> requested = List.of(2001L);
        RefundScopeMismatchException ex = new RefundScopeMismatchException(existing, requested);

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.REFUND_SCOPE_MISMATCH);
        assertThat(ex.existing()).containsExactly(2001L, 2002L);
        assertThat(ex.requested()).containsExactly(2001L);
    }

    @Test
    void refundScopeMismatchException_listsImmutable() {
        RefundScopeMismatchException ex = new RefundScopeMismatchException(
                List.of(2001L), List.of(2001L));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ex.existing().add(9999L));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ex.requested().add(9999L));
    }

    @Test
    void outOfStockException_carriesMessage() {
        OutOfStockException ex = new OutOfStockException("sku-001", 5L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
        assertThat(ex.getMessage()).contains("sku-001").contains("5");
    }

    @Test
    void warehouseNotFoundException_carriesId() {
        WarehouseNotFoundException ex = new WarehouseNotFoundException(42L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThat(ex.getMessage()).contains("42");
    }

    @Test
    void skuSuspendedException_carriesReason() {
        SkuSuspendedException ex = new SkuSuspendedException("sku-001", 5L, "INVARIANT_DRIFT");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.SKU_SUSPENDED);
        assertThat(ex.getMessage()).contains("INVARIANT_DRIFT");
    }

    @Test
    void invariantDriftException_errorCode() {
        InvariantDriftException ex = new InvariantDriftException("sku-001", 5L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVARIANT_DRIFT);
    }

    @Test
    void insufficientStockException_errorCode() {
        InsufficientStockException ex = new InsufficientStockException("sku-001");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void insufficientReservedException_errorCode() {
        InsufficientReservedException ex = new InsufficientReservedException("sku-001", 5L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_RESERVED);
    }

    @Test
    void releaseUnderflowException_errorCode() {
        ReleaseUnderflowException ex = new ReleaseUnderflowException("sku-001", 5L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.RELEASE_UNDERFLOW);
    }

    @Test
    void reservationNotFoundException_errorCode() {
        ReservationNotFoundException ex = new ReservationNotFoundException("order=10001");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void skuNotSuspendedException_errorCode() {
        SkuNotSuspendedException ex = new SkuNotSuspendedException("sku-001", 5L);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.SKU_NOT_SUSPENDED);
    }
}