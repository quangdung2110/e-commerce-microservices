package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PhysicalInventoryJpaCustomImpl implements PhysicalInventoryJpaCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public List<AvailableStockProjection> findAvailableBySkuIds(Collection<String> skuIds) {
        if (skuIds.isEmpty()) return List.of();
        return em.createNativeQuery("""
            SELECT sku_id, product_id, warehouse_id, available_quantity
            FROM physical_inventory
            WHERE sku_id = ANY(:sku_ids)
              AND available_quantity > 0
            ORDER BY sku_id, warehouse_id
            """)
            .setParameter("sku_ids", skuIds.toArray(new String[0]))
            .unwrap(NativeQuery.class)
            .setTupleTransformer((Object[] tuple, String[] aliases) ->
                new AvailableStockProjection(
                    (String) tuple[0],
                    (String) tuple[1],
                    ((Number) tuple[2]).longValue(),
                    ((Number) tuple[3]).longValue()))
            .getResultList();
    }

    @Override
    public Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery("""
            SELECT sku_id, COALESCE(SUM(available_quantity), 0)
            FROM physical_inventory
            WHERE sku_id = ANY(:sku_ids)
            GROUP BY sku_id
            """)
            .setParameter("sku_ids", skuIds.toArray(new String[0]))
            .getResultList();
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Override
    public int bulkAdjustReserved(Collection<ReservedAdjustment> adjustments) {
        if (adjustments.isEmpty()) return 0;
        int total = 0;
        for (ReservedAdjustment adj : adjustments) {
            int updated = em.createNativeQuery("""
                UPDATE physical_inventory
                SET reserved_quantity = reserved_quantity + :delta,
                    updated_at = NOW()
                WHERE sku_id = :sku AND warehouse_id = :wh
                """)
                .setParameter("delta", adj.delta())
                .setParameter("sku", adj.skuId())
                .setParameter("wh", adj.warehouseId())
                .executeUpdate();
            total += updated;
        }
        return total;
    }

    @Override
    public int bulkUpsert(Collection<PhysicalInventoryUpsert> upserts) {
        if (upserts.isEmpty()) return 0;
        int total = 0;
        for (PhysicalInventoryUpsert upsert : upserts) {
            int updated = em.createNativeQuery("""
                INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
                VALUES (:sku, :product, :wh, :actual, 0, NOW())
                ON CONFLICT (sku_id, warehouse_id)
                DO UPDATE SET actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
                              updated_at = NOW()
                """)
                .setParameter("sku", upsert.skuId())
                .setParameter("product", upsert.productId())
                .setParameter("wh", upsert.warehouseId())
                .setParameter("actual", upsert.actualQty())
                .executeUpdate();
            total += updated;
        }
        return total;
    }
}
