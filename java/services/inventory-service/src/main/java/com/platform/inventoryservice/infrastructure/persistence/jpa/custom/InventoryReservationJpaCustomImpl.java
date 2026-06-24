package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryReservationJpaEntity;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.InventoryReservationJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryReservationJpaCustomImpl implements InventoryReservationJpaCustom {

    @PersistenceContext
    private EntityManager em;

    private final InventoryReservationJpaRepository repository;

    public InventoryReservationJpaCustomImpl(@Lazy InventoryReservationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Long> insertIfAbsent(InventoryReservationJpaEntity e) {
        try {
            em.persist(e);
            em.flush();
            return Optional.of(e.getId());
        } catch (PersistenceException ex) {
            if (isUniqueViolation(ex)) {
                return repository
                    .findFirstByOrderIdAndSkuIdAndWarehouseId(
                        e.getOrderId(), e.getSkuId(), e.getWarehouseId())
                    .map(InventoryReservationJpaEntity::getId);
            }
            throw ex;
        }
    }

    @Override
    public int bulkUpdateStatus(Collection<ReservationStatusUpdate> updates) {
        if (updates.isEmpty()) return 0;
        int total = 0;
        for (ReservationStatusUpdate update : updates) {
            total += em.createNativeQuery("""
                UPDATE inventory_reservations
                SET status = :status, updated_at = NOW()
                WHERE id = :id
                """)
                .setParameter("status", update.newStatus().name())
                .setParameter("id", update.id())
                .executeUpdate();
        }
        return total;
    }

    @Override
    public RefundCompletionProjection findRefundCompletion(long orderId, String refundRequestId) {
        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT
              COUNT(*) FILTER (WHERE refund_returned_at IS NULL
                               AND status = 'DEDUCTED'
                               AND refund_expected = TRUE),
              array_agg(order_item_id) FILTER (WHERE refund_expected = TRUE),
              array_agg(order_item_id) FILTER (WHERE refund_expected = TRUE
                                                AND refund_returned_at IS NOT NULL)
            FROM inventory_reservations
            WHERE order_id = :order_id
              AND refund_request_id = :rrid
            """)
            .setParameter("order_id", orderId)
            .setParameter("rrid", refundRequestId)
            .getSingleResult();

        long pending = row[0] == null ? 0 : ((Number) row[0]).longValue();
        Long[] requiredArr = (Long[]) row[1];
        Long[] receivedArr = (Long[]) row[2];
        return new RefundCompletionProjection(
            pending,
            requiredArr == null ? List.of() : Arrays.asList(requiredArr),
            receivedArr == null ? List.of() : Arrays.asList(receivedArr));
    }

    @Override
    public int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds) {
        if (orderItemIds.isEmpty()) return 0;
        return em.createNativeQuery("""
            UPDATE inventory_reservations
            SET refund_expected = TRUE,
                refund_request_id = :rrid,
                updated_at = NOW()
            WHERE order_id = :order_id
              AND order_item_id = ANY(:item_ids)
            """)
            .setParameter("order_id", orderId)
            .setParameter("rrid", refundRequestId)
            .setParameter("item_ids", orderItemIds.toArray(new Long[0]))
            .executeUpdate();
    }

    @Override
    public int markRefundReturned(long orderId, long orderItemId) {
        return em.createNativeQuery("""
            UPDATE inventory_reservations
            SET refund_returned_at = NOW(),
                updated_at = NOW()
            WHERE order_id = :order_id
              AND order_item_id = :order_item_id
              AND refund_returned_at IS NULL
            """)
            .setParameter("order_id", orderId)
            .setParameter("order_item_id", orderItemId)
            .executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> findDeductedItemIds(long orderId, long warehouseId) {
        return em.createNativeQuery("""
            SELECT order_item_id FROM inventory_reservations
            WHERE order_id = :order_id
              AND warehouse_id = :warehouse_id
              AND status = 'DEDUCTED'
            """)
            .setParameter("order_id", orderId)
            .setParameter("warehouse_id", warehouseId)
            .getResultList();
    }

    private boolean isUniqueViolation(PersistenceException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("duplicate key value violates unique constraint")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
