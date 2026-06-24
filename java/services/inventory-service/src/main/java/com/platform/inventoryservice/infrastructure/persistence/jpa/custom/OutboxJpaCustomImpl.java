package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class OutboxJpaCustomImpl implements OutboxJpaCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int deleteBatchOlderThan(Instant watermark, int limit) {
        return em.createNativeQuery("""
            DELETE FROM inventory_outbox
            WHERE event_id IN (
              SELECT event_id FROM inventory_outbox
              WHERE created_at < :watermark
              ORDER BY created_at
              LIMIT :limit
            )
            """)
            .setParameter("watermark", watermark)
            .setParameter("limit", limit)
            .executeUpdate();
    }

    @Override
    public boolean existsReturnReceivedAll(String orderId, String refundRequestId) {
        Number count = (Number) em.createNativeQuery("""
            SELECT count(1) FROM inventory_outbox
            WHERE aggregate_id = :order_id
              AND refund_request_id = :rrid
              AND event_type = 'INVENTORY_RETURN_RECEIVED_ALL'
            """)
            .setParameter("order_id", orderId)
            .setParameter("rrid", refundRequestId)
            .getSingleResult();
        return count != null && count.longValue() > 0;
    }
}
