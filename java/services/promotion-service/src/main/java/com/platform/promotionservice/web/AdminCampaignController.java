package com.platform.promotionservice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.service.CampaignAdminService;
import com.platform.promotionservice.web.dto.CampaignDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Admin campaigns (contracts/http/promotion/v1/openapi.yaml, tag Admin campaigns). */
@RestController
@RequestMapping("/api/v1/admin/promotion-campaigns")
@RequiredArgsConstructor
public class AdminCampaignController {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private final CampaignAdminService campaigns;

    @GetMapping
    public CampaignDtos.CampaignPage list(@RequestParam(name = "publication_status", required = false) PublicationStatus status,
                                          @RequestParam(name = "phase", required = false) CampaignPhase phase,
                                          @RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) Integer limit) {
        return campaigns.list(status, phase, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<CampaignDtos.CampaignResponse> create(@Valid @RequestBody CampaignDtos.CreateCampaignRequest request) {
        CampaignEntity campaign = campaigns.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/promotion-campaigns/" + campaign.getId()))
                .eTag(campaign.etag())
                .body(CampaignDtos.CampaignResponse.from(campaign, campaigns.now()));
    }

    @GetMapping("/{campaign_id}")
    public ResponseEntity<CampaignDtos.CampaignResponse> get(@PathVariable("campaign_id") String campaignId) {
        CampaignEntity campaign = campaigns.get(campaignId);
        return ResponseEntity.ok().eTag(campaign.etag())
                .body(CampaignDtos.CampaignResponse.from(campaign, campaigns.now()));
    }

    @PatchMapping(value = "/{campaign_id}", consumes = MERGE_PATCH)
    public ResponseEntity<CampaignDtos.CampaignResponse> update(@PathVariable("campaign_id") String campaignId,
                                                                @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                @RequestBody JsonNode patch) {
        CampaignEntity campaign = campaigns.update(campaignId, ifMatch, patch);
        return ResponseEntity.ok().eTag(campaign.etag())
                .body(CampaignDtos.CampaignResponse.from(campaign, campaigns.now()));
    }

    @PostMapping("/{campaign_id}/copies")
    public ResponseEntity<CampaignDtos.CampaignResponse> clone(@PathVariable("campaign_id") String campaignId,
                                                               @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                               @Valid @RequestBody CampaignDtos.CloneCampaignRequest request) {
        CampaignEntity campaign = campaigns.clone(campaignId, ifMatch, request);
        return ResponseEntity.created(URI.create("/api/v1/admin/promotion-campaigns/" + campaign.getId()))
                .eTag(campaign.etag())
                .body(CampaignDtos.CampaignResponse.from(campaign, campaigns.now()));
    }

    @PostMapping("/{campaign_id}/transitions")
    public ResponseEntity<CampaignDtos.CampaignResponse> transition(@PathVariable("campaign_id") String campaignId,
                                                                    @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                    @Valid @RequestBody CampaignDtos.CampaignTransitionRequest request) {
        CampaignEntity campaign = campaigns.transition(campaignId, ifMatch, request);
        return ResponseEntity.status(HttpStatus.OK).eTag(campaign.etag())
                .body(CampaignDtos.CampaignResponse.from(campaign, campaigns.now()));
    }
}
