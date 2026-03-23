package com.ecommerce.shipping.infrastructure.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Theo thiết kế shipping_db_design.md mục 2.1: bảng shipments.
@Entity
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    private Long id;

    @Column(name = "parcel_id", nullable = false, unique = true)
    private Long parcelId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ShippingProviderEntity provider;

    @Column(name = "tracking_number", unique = true, length = 100)
    private String trackingNumber;

    @Column(name = "sender_info", columnDefinition = "jsonb")
    private String senderInfo;

    @Column(name = "receiver_info", columnDefinition = "jsonb")
    private String receiverInfo;

    @Column(name = "package_info", columnDefinition = "jsonb")
    private String packageInfo;

    @Column(name = "standardized_status", nullable = false, length = 50)
    private String standardizedStatus;

    @Column(name = "provider_status", length = 100)
    private String providerStatus;

    @Column(name = "shipping_fee", precision = 19, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "cod_amount", precision = 19, scale = 2)
    private BigDecimal codAmount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParcelId() {
        return parcelId;
    }

    public void setParcelId(Long parcelId) {
        this.parcelId = parcelId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public ShippingProviderEntity getProvider() {
        return provider;
    }

    public void setProvider(ShippingProviderEntity provider) {
        this.provider = provider;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getSenderInfo() {
        return senderInfo;
    }

    public void setSenderInfo(String senderInfo) {
        this.senderInfo = senderInfo;
    }

    public String getReceiverInfo() {
        return receiverInfo;
    }

    public void setReceiverInfo(String receiverInfo) {
        this.receiverInfo = receiverInfo;
    }

    public String getPackageInfo() {
        return packageInfo;
    }

    public void setPackageInfo(String packageInfo) {
        this.packageInfo = packageInfo;
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

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public void setCodAmount(BigDecimal codAmount) {
        this.codAmount = codAmount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
