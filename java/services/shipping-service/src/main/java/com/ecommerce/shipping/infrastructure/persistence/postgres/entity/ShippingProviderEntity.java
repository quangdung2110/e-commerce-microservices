package com.ecommerce.shipping.infrastructure.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Theo thiết kế shipping_db_design.md mục 1.1: bảng shipping_providers.
@Entity
@Table(name = "shipping_providers")
public class ShippingProviderEntity {

    @Id
    private Integer id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "api_config", columnDefinition = "jsonb")
    private String apiConfig;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getApiConfig() {
        return apiConfig;
    }

    public void setApiConfig(String apiConfig) {
        this.apiConfig = apiConfig;
    }
}
