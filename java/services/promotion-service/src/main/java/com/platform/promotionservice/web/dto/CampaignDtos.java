package com.platform.promotionservice.web.dto;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** DTO cho Admin campaigns (openapi tag Admin campaigns). */
public final class CampaignDtos {

    private CampaignDtos() {
    }

    public static final String CODE_PATTERN = "^[A-Z0-9][A-Z0-9_-]*$";

    public record CreateCampaignRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description,
            @NotNull Instant startTime,
            @NotNull Instant endTime) {
    }

    public record PromotionCodeOverride(
            @NotBlank String sourcePromotionId,
            @NotBlank @Size(max = 80) @Pattern(regexp = CODE_PATTERN) String newCode) {
    }

    public record CloneCampaignRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description,
            @NotNull Instant startTime,
            @NotNull Instant endTime,
            @NotNull @Size(max = 100) List<PromotionCodeOverride> promotionCodeOverrides) {
    }

    public record CampaignTransitionRequest(
            @NotNull PublicationStatus targetStatus,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record CampaignResponse(
            String id,
            String code,
            String name,
            String description,
            PublicationStatus publicationStatus,
            CampaignPhase phase,
            Instant startTime,
            Instant endTime,
            long revision,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {

        public static CampaignResponse from(CampaignEntity campaign, Instant now) {
            return new CampaignResponse(
                    Long.toString(campaign.getId()),
                    campaign.getCode(),
                    campaign.getName(),
                    campaign.getDescription(),
                    campaign.getPublicationStatus(),
                    campaign.phaseAt(now),
                    campaign.getStartTime(),
                    campaign.getEndTime(),
                    campaign.getRevision(),
                    campaign.getCreatedBy(),
                    campaign.getUpdatedBy(),
                    campaign.getCreatedAt(),
                    campaign.getUpdatedAt());
        }
    }

    public record CampaignPage(List<CampaignResponse> items, String nextCursor) {
    }
}
