package com.ecommerce.shipping.infrastructure.persistence.scylla.repository;

import com.ecommerce.shipping.infrastructure.persistence.scylla.entity.ShipmentEventPrimaryKey;
import com.ecommerce.shipping.infrastructure.persistence.scylla.entity.ShipmentEventRow;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// Theo thiết kế shipping_workflow.md mục 4: query tracking events ordered by timestamp desc.
public interface ShipmentEventScyllaRepository extends CassandraRepository<ShipmentEventRow, ShipmentEventPrimaryKey> {

    @Query("SELECT * FROM shipment_events WHERE shipment_id = :shipmentId LIMIT :limit")
    List<ShipmentEventRow> findLatestByShipmentId(@Param("shipmentId") Long shipmentId,
                                                  @Param("limit") int limit);
}
