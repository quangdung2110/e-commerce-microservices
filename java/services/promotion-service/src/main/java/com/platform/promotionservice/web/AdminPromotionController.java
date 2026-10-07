package com.platform.promotionservice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.service.PromotionAdminService;
import com.platform.promotionservice.service.WalletService;
import com.platform.promotionservice.web.dto.PromotionDtos;
import com.platform.promotionservice.web.dto.WalletDtos;
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
import java.util.UUID;

/** Admin promotions: voucher definition, budget và direct grant. */
@RestController
@RequiredArgsConstructor
public class AdminPromotionController {

    private final PromotionAdminService promotions;
    private final WalletService wallets;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/v1/admin/promotion-campaigns/{campaign_id}/promotions")
    public PromotionDtos.PromotionPage list(@PathVariable("campaign_id") String campaignId,
                                            @RequestParam(name = "cursor", required = false) String cursor,
                                            @RequestParam(name = "limit", required = false) Integer limit) {
        return promotions.list(campaignId, cursor, limit);
    }

    @PostMapping("/api/v1/admin/promotion-campaigns/{campaign_id}/promotions")
    public ResponseEntity<PromotionDtos.PromotionResponse> create(@PathVariable("campaign_id") String campaignId,
                                                                  @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                  @Valid @RequestBody PromotionDtos.CreatePromotionRequest request) {
        PromotionAdminService.Loaded loaded = promotions.create(campaignId, ifMatch, request);
        return ResponseEntity.created(URI.create("/api/v1/admin/promotions/" + loaded.promotion().getId()))
                .eTag(loaded.campaign().etag())
                .body(loaded.toResponse(objectMapper));
    }

    @GetMapping("/api/v1/admin/promotions/{promotion_id}")
    public ResponseEntity<PromotionDtos.PromotionResponse> get(@PathVariable("promotion_id") String promotionId) {
        PromotionAdminService.Loaded loaded = promotions.get(promotionId);
        return ResponseEntity.ok().eTag(loaded.campaign().etag()).body(loaded.toResponse(objectMapper));
    }

    @PatchMapping(value = "/api/v1/admin/promotions/{promotion_id}", consumes = AdminCampaignController.MERGE_PATCH)
    public ResponseEntity<PromotionDtos.PromotionResponse> update(@PathVariable("promotion_id") String promotionId,
                                                                  @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                  @RequestBody JsonNode patch) {
        PromotionAdminService.Loaded loaded = promotions.update(promotionId, ifMatch, patch);
        return ResponseEntity.ok().eTag(loaded.campaign().etag()).body(loaded.toResponse(objectMapper));
    }

    @PostMapping("/api/v1/admin/promotions/{promotion_id}/grants")
    public ResponseEntity<WalletDtos.WalletVoucher> grant(@PathVariable("promotion_id") String promotionId,
                                                          @Valid @RequestBody PromotionDtos.GrantVoucherRequest request) {
        UUID userId = Ids.parseUuid(request.userId(), "user_id");
        WalletService.SaveOutcome outcome = wallets.grant(userId, Ids.parse(promotionId, "promotion_id"));
        WalletDtos.WalletVoucher body = wallets.toDto(outcome.wallet());
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/api/v1/me/promotion-vouchers/" + promotionId)).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
