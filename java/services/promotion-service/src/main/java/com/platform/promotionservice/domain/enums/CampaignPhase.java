package com.platform.promotionservice.domain.enums;

/** Phase suy ra từ publication_status và [start_time,end_time); không persist. */
public enum CampaignPhase {
    DRAFT, UPCOMING, ACTIVE, ENDED, PAUSED, CANCELLED
}
