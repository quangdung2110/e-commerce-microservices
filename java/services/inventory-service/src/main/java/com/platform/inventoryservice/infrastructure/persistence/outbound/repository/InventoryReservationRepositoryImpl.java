package com.platform.inventoryservice.infrastructure.persistence.outbound.repository;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.InventoryReservationJpaCustom.RefundCompletionProjection;
import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.InventoryReservationJpaCustom.ReservationStatusUpdate;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryReservationJpaEntity;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.InventoryReservationJpaRepository;
import com.platform.inventoryservice.infrastructure.persistence.outbound.mapper.InventoryReservationMapper;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InventoryReservationRepositoryImpl implements InventoryReservationRepository {

    private final InventoryReservationJpaRepository jpa;
    private final InventoryReservationMapper mapper;

    @Override
    @Transactional
    public Optional<Long> insertIfAbsent(InventoryReservation domain) {
        InventoryReservationJpaEntity entity = mapper.toJpa(domain);
        return jpa.insertIfAbsent(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryReservation> findActiveByOrderSkuWarehouse(long orderId, String skuId, long warehouseId) {
        return jpa.findFirstByOrderIdAndSkuIdAndWarehouseId(orderId, skuId, warehouseId)
            .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryReservation> findByOrderId(long orderId) {
        return jpa.findByOrderId(orderId).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional
    public void updateStatus(long id, ReservationStatus status) {
        jpa.bulkUpdateStatus(List.of(new ReservationStatusUpdate(id, status)));
    }

    @Override
    @Transactional
    public int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds) {
        return jpa.markRefundExpected(orderId, refundRequestId, orderItemIds);
    }

    @Override
    @Transactional
    public int markRefundReturned(long orderId, long orderItemId) {
        return jpa.markRefundReturned(orderId, orderItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundCompletionSnapshot checkRefundCompletion(long orderId, String refundRequestId) {
        RefundCompletionProjection p = jpa.findRefundCompletion(orderId, refundRequestId);
        return new RefundCompletionSnapshot(p.pendingCount(), p.requiredItemIds(), p.receivedItemIds());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDeductedItemIds(long orderId, long warehouseId) {
        return jpa.findDeductedItemIds(orderId, warehouseId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryReservation> findByOrderIdAndOrderItemIdAndStatus(long orderId, long orderItemId, ReservationStatus status) {
        return jpa.findFirstByOrderIdAndOrderItemIdAndStatus(orderId, orderItemId, status).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryReservation> findByOrderIdAndOrderItemIdInAndStatus(long orderId, List<Long> orderItemIds, ReservationStatus status) {
        return jpa.findByOrderIdAndOrderItemIdInAndStatus(orderId, orderItemIds, status).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryReservation> findRefundExpectedByOrderIdAndRefundRequestId(long orderId, String refundRequestId) {
        return jpa.findByOrderIdAndRefundRequestIdAndRefundExpectedTrue(orderId, refundRequestId).stream().map(mapper::toDomain).toList();
    }
}
