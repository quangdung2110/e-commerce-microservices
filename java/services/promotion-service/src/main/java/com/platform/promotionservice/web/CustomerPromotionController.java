package com.platform.promotionservice.web;

import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.service.ClaimService;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.service.WalletService;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

/**
 * Customer promotions: suggestions, discovery, save và limited claim. Identity user
 * lấy từ trusted header; response cá nhân trả {@code Cache-Control: no-store}.
 */
@RestController
@RequiredArgsConstructor
public class CustomerPromotionController {

    private final DiscoveryService discovery;
    private final WalletService wallets;
    private final ClaimService claims;
    private final PromotionProperties properties;
    private final Clock clock;

    @PostMapping("/api/v1/me/promotion-voucher-suggestions")
    public ResponseEntity<CheckoutDtos.VoucherSuggestionResponse> suggest(
            @Valid @RequestBody CheckoutDtos.SuggestionRequest request) {
        UUID userId = CallerContext.requireUserId();
        return noStore().body(discovery.suggest(userId, request));
    }

    @PostMapping("/api/v1/promotion-discovery")
    public ResponseEntity<CheckoutDtos.VoucherSuggestionResponse> discover(
            @Valid @RequestBody CheckoutDtos.DiscoveryRequest request) {
        UUID userId = CallerContext.requireUserId();
        return noStore().body(discovery.discover(userId, request));
    }

    @PutMapping("/api/v1/me/promotion-vouchers/{promotion_id}")
    public ResponseEntity<CheckoutDtos.SaveAndEvaluateResponse> save(
            @PathVariable("promotion_id") String promotionId,
            @Valid @RequestBody(required = false) CheckoutDtos.SaveAndEvaluateRequest request) {
        UUID userId = CallerContext.requireUserId();
        WalletService.SaveOutcome outcome = wallets.savePublic(userId, Ids.parse(promotionId, "promotion_id"));
        CheckoutDtos.SaveAndEvaluateResponse body = new CheckoutDtos.SaveAndEvaluateResponse(
                wallets.toDto(outcome.wallet()),
                // Without a context there is nothing to evaluate against: save only (Promotion ADR-018).
                request == null || request.context() == null ? null
                        : wallets.evaluateWallet(outcome.wallet(), userId, request.context(), clock.instant()));
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/api/v1/me/promotion-vouchers/" + promotionId))
                    .cacheControl(CacheControl.noStore()).body(body);
        }
        return noStore().body(body);
    }

    @PostMapping("/api/v1/me/promotion-voucher-claims")
    public ResponseEntity<?> claim(@Valid @RequestBody CheckoutDtos.ClaimVoucherRequest request) {
        UUID userId = CallerContext.requireUserId();
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        ClaimService.Outcome outcome = claims.claim(userId, promotionId, request.expectedCampaignRevision());
        return switch (outcome) {
            case ClaimService.Committed committed -> committed.created()
                    ? ResponseEntity.created(URI.create("/api/v1/me/promotion-vouchers/" + request.promotionId()))
                    .cacheControl(CacheControl.noStore()).body(committed.response())
                    : noStore().body(committed.response());
            case ClaimService.Pending pending -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.RETRY_AFTER, Integer.toString(properties.getClaim().getPendingRetryAfterSeconds()))
                    .body(pending.response());
        };
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore());
    }
}
