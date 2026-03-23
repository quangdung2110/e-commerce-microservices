package com.ecommerce.shipping.infrastructure.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

// Theo thiết kế shipping_state_machine.md mục 2: bảng provider_status_mappings.
@Entity
@Table(name = "provider_status_mappings")
public class ProviderStatusMappingEntity {

    @Id
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ShippingProviderEntity provider;

    @Column(name = "provider_status_code", nullable = false, length = 100)
    private String providerStatusCode;

    @Column(name = "standardized_status", nullable = false, length = 50)
    private String standardizedStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ShippingProviderEntity getProvider() {
        return provider;
    }

    public void setProvider(ShippingProviderEntity provider) {
        this.provider = provider;
    }

    public String getProviderStatusCode() {
        return providerStatusCode;
    }

    public void setProviderStatusCode(String providerStatusCode) {
        this.providerStatusCode = providerStatusCode;
    }

    public String getStandardizedStatus() {
        return standardizedStatus;
    }

    public void setStandardizedStatus(String standardizedStatus) {
        this.standardizedStatus = standardizedStatus;
    }
}
