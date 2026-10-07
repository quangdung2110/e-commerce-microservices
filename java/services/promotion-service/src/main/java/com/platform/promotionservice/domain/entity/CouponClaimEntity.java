package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.ClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Một suất pre-generated cho distribution LIMITED_CLAIM. */
@Entity
@Table(name = "coupon_claims")
@Getter
@Setter
public class CouponClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status = ClaimStatus.AVAILABLE;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    /** Campaign revision đã được claim-gate Lua kiểm atomically với SPOP. */
    @Column(name = "claimed_campaign_revision")
    private Long claimedCampaignRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
