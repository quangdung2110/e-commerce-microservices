package com.ecommerce.promotion.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request body cho tạo/cập nhật Campaign.
 * Theo thiết kế promotion_db_design.md - Section 1.1
 */
public class CampaignRequest {

    @NotBlank(message = "Campaign name is required")
    private String name;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    @NotNull(message = "End time is required")
    private Instant endTime;

    private String status; // UPCOMING, ACTIVE, ENDED, SUSPENDED — optional trên update

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
