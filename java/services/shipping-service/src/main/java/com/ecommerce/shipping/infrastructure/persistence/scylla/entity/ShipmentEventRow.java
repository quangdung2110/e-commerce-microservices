package com.ecommerce.shipping.infrastructure.persistence.scylla.entity;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

// Theo thiết kế shipping_db_design.md mục 2.2: ScyllaDB shipment_events.
@Table("shipment_events")
public class ShipmentEventRow {

    @PrimaryKey
    private ShipmentEventPrimaryKey primaryKey;

    @Column("standardized_status")
    private String standardizedStatus;

    @Column("provider_status")
    private String providerStatus;

    @Column("description")
    private String description;

    @Column("location")
    private String location;

    @Column("raw_payload")
    private String rawPayload;

    public ShipmentEventPrimaryKey getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(ShipmentEventPrimaryKey primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getStandardizedStatus() {
        return standardizedStatus;
    }

    public void setStandardizedStatus(String standardizedStatus) {
        this.standardizedStatus = standardizedStatus;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }
}
