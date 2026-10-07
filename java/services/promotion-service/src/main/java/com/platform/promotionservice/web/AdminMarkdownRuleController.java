package com.platform.promotionservice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.service.MarkdownAdminService;
import com.platform.promotionservice.web.dto.MarkdownDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Admin markdown rules (product-scope, campaign DRAFT). */
@RestController
@RequiredArgsConstructor
public class AdminMarkdownRuleController {

    private final MarkdownAdminService markdown;

    @GetMapping("/api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules")
    public MarkdownDtos.MarkdownRulePage list(@PathVariable("campaign_id") String campaignId,
                                              @RequestParam(name = "cursor", required = false) String cursor,
                                              @RequestParam(name = "limit", required = false) Integer limit) {
        return markdown.list(campaignId, cursor, limit);
    }

    @PostMapping("/api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules")
    public ResponseEntity<MarkdownDtos.MarkdownRuleResponse> create(@PathVariable("campaign_id") String campaignId,
                                                                    @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                    @Valid @RequestBody MarkdownDtos.CreateMarkdownRuleRequest request) {
        MarkdownAdminService.Loaded loaded = markdown.create(campaignId, ifMatch, request);
        return ResponseEntity.created(URI.create("/api/v1/admin/markdown-rules/" + loaded.rule().getId()))
                .eTag(loaded.campaign().etag())
                .body(loaded.toResponse());
    }

    @GetMapping("/api/v1/admin/markdown-rules/{markdown_rule_id}")
    public ResponseEntity<MarkdownDtos.MarkdownRuleResponse> get(@PathVariable("markdown_rule_id") String ruleId) {
        MarkdownAdminService.Loaded loaded = markdown.get(ruleId);
        return ResponseEntity.ok().eTag(loaded.campaign().etag()).body(loaded.toResponse());
    }

    @PatchMapping(value = "/api/v1/admin/markdown-rules/{markdown_rule_id}", consumes = AdminCampaignController.MERGE_PATCH)
    public ResponseEntity<MarkdownDtos.MarkdownRuleResponse> update(@PathVariable("markdown_rule_id") String ruleId,
                                                                    @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                    @RequestBody JsonNode patch) {
        MarkdownAdminService.Loaded loaded = markdown.update(ruleId, ifMatch, patch);
        return ResponseEntity.ok().eTag(loaded.campaign().etag()).body(loaded.toResponse());
    }
}
