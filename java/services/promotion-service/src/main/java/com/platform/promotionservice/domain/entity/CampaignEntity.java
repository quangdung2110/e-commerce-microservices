package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.PublicationStatus;
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

/**
 * Campaign: aggregate quản trị/lịch dùng chung cho voucher và markdown.
 * {@code revision} là optimistic-concurrency/cache/schedule fence, không phải
 * definition version (PROMO-BR-009).
 */
@Entity
@Table(name = "campaigns")
@Getter
@Setter
public class CampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 16)
    private PublicationStatus publicationStatus = PublicationStatus.DRAFT;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private long revision = 1;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isDraft() {
        return publicationStatus == PublicationStatus.DRAFT;
    }

    /** Phase theo docs/promotion/domain.md §5, interval nửa mở [start,end). */
    public CampaignPhase phaseAt(Instant now) {
        return switch (publicationStatus) {
            case DRAFT -> CampaignPhase.DRAFT;
            case PAUSED -> CampaignPhase.PAUSED;
            case CANCELLED -> CampaignPhase.CANCELLED;
            case PUBLISHED -> {
                if (now.isBefore(startTime)) {
                    yield CampaignPhase.UPCOMING;
                }
                yield now.isBefore(endTime) ? CampaignPhase.ACTIVE : CampaignPhase.ENDED;
            }
        };
    }

    public boolean isActiveAt(Instant now) {
        return phaseAt(now) == CampaignPhase.ACTIVE;
    }

    public String etag() {
        return etagOf(revision);
    }

    public static String etagOf(long revision) {
        return "\"" + revision + "\"";
    }

    public void bumpRevision(String actor, Instant now) {
        this.revision++;
        this.updatedBy = actor;
        this.updatedAt = now;
    }
}
