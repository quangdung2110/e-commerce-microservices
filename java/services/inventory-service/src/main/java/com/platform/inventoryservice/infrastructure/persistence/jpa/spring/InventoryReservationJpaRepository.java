package com.platform.inventoryservice.infrastructure.persistence.jpa.spring;

import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.InventoryReservationJpaCustom;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryReservationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InventoryReservationJpaRepository
        extends JpaRepository<InventoryReservationJpaEntity, Long>,
                InventoryReservationJpaCustom {

    Optional<InventoryReservationJpaEntity> findFirstByOrderIdAndSkuIdAndWarehouseId(
        Long orderId, String skuId, Long warehouseId);

    List<InventoryReservationJpaEntity> findByOrderId(Long orderId);

    List<InventoryReservationJpaEntity> findByOrderIdAndOrderItemId(Long orderId, Long orderItemId);

    Optional<InventoryReservationJpaEntity> findFirstByOrderIdAndOrderItemIdAndStatus(Long orderId, Long orderItemId, ReservationStatus status);

    List<InventoryReservationJpaEntity> findByOrderIdAndOrderItemIdInAndStatus(Long orderId, List<Long> orderItemIds, ReservationStatus status);

    List<InventoryReservationJpaEntity> findByOrderIdAndRefundRequestIdAndRefundExpectedTrue(Long orderId, String refundRequestId);

    @Query("SELECT r FROM InventoryReservationJpaEntity r " +
           "WHERE r.status = :status AND r.expiresAt < :now")
    List<InventoryReservationJpaEntity> findExpired(
        @Param("status") ReservationStatus status,
        @Param("now") Instant now);
}
