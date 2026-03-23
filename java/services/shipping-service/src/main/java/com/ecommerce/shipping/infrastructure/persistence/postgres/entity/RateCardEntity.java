package com.ecommerce.shipping.infrastructure.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

// Theo thiết kế shipping_db_design.md mục 1.2: bảng rate_cards.
@Entity
@Table(name = "rate_cards")
public class RateCardEntity {

    @Id
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ShippingProviderEntity provider;

    @Column(name = "from_province_code", nullable = false, length = 20)
    private String fromProvinceCode;

    @Column(name = "from_district_code", length = 20)
    private String fromDistrictCode;

    @Column(name = "to_province_code", nullable = false, length = 20)
    private String toProvinceCode;

    @Column(name = "to_district_code", length = 20)
    private String toDistrictCode;

    @Column(name = "to_ward_code", length = 20)
    private String toWardCode;

    @Column(name = "base_weight", nullable = false)
    private Integer baseWeight;

    @Column(name = "base_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal baseFee;

    @Column(name = "step_weight", nullable = false)
    private Integer stepWeight;

    @Column(name = "step_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal stepFee;

    @Column(name = "estimated_days", nullable = false)
    private Integer estimatedDays;

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

    public String getFromProvinceCode() {
        return fromProvinceCode;
    }

    public void setFromProvinceCode(String fromProvinceCode) {
        this.fromProvinceCode = fromProvinceCode;
    }

    public String getFromDistrictCode() {
        return fromDistrictCode;
    }

    public void setFromDistrictCode(String fromDistrictCode) {
        this.fromDistrictCode = fromDistrictCode;
    }

    public String getToProvinceCode() {
        return toProvinceCode;
    }

    public void setToProvinceCode(String toProvinceCode) {
        this.toProvinceCode = toProvinceCode;
    }

    public String getToDistrictCode() {
        return toDistrictCode;
    }

    public void setToDistrictCode(String toDistrictCode) {
        this.toDistrictCode = toDistrictCode;
    }

    public String getToWardCode() {
        return toWardCode;
    }

    public void setToWardCode(String toWardCode) {
        this.toWardCode = toWardCode;
    }

    public Integer getBaseWeight() {
        return baseWeight;
    }

    public void setBaseWeight(Integer baseWeight) {
        this.baseWeight = baseWeight;
    }

    public BigDecimal getBaseFee() {
        return baseFee;
    }

    public void setBaseFee(BigDecimal baseFee) {
        this.baseFee = baseFee;
    }

    public Integer getStepWeight() {
        return stepWeight;
    }

    public void setStepWeight(Integer stepWeight) {
        this.stepWeight = stepWeight;
    }

    public BigDecimal getStepFee() {
        return stepFee;
    }

    public void setStepFee(BigDecimal stepFee) {
        this.stepFee = stepFee;
    }

    public Integer getEstimatedDays() {
        return estimatedDays;
    }

    public void setEstimatedDays(Integer estimatedDays) {
        this.estimatedDays = estimatedDays;
    }
}
