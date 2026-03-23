package com.ecommerce.shipping.infrastructure.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

// Theo thiết kế shipping_db_design.md mục 3.1: bảng idempotency_keys.
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    private String key;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public OffsetDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(OffsetDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
