package com.ecommerce.shipping.infrastructure.persistence.scylla.entity;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

// Theo thiết kế shipping_db_design.md mục 2.2: PK shipment_id + CK occurred_at DESC.
@PrimaryKeyClass
public class ShipmentEventPrimaryKey implements Serializable {

    @PrimaryKeyColumn(name = "shipment_id", type = PrimaryKeyType.PARTITIONED)
    private Long shipmentId;

    @PrimaryKeyColumn(name = "occurred_at", ordinal = 0, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant occurredAt;

    public ShipmentEventPrimaryKey() {
    }

    public ShipmentEventPrimaryKey(Long shipmentId, Instant occurredAt) {
        this.shipmentId = shipmentId;
        this.occurredAt = occurredAt;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ShipmentEventPrimaryKey that)) {
            return false;
        }
        return Objects.equals(shipmentId, that.shipmentId) && Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shipmentId, occurredAt);
    }
}
