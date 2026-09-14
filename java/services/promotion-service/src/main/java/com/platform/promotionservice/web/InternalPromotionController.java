package com.platform.promotionservice.web;

import com.platform.promotionservice.service.AllocationService;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.service.FinalCommandService;
import com.platform.promotionservice.web.dto.AllocationDtos;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Internal routes: Checkout discount calculation (pure/replay-safe) và Order/Temporal
 * allocation lifecycle TRY/CONFIRM/CANCEL/technical cancellation.
 */
@RestController
@RequiredArgsConstructor
public class InternalPromotionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final DiscoveryService discovery;
    private final AllocationService allocation;
    private final FinalCommandService finalCommands;

    @PostMapping("/internal/v1/promotion-discount-calculations")
    public CheckoutDtos.DiscountCalculationResult calculate(
            @Valid @RequestBody CheckoutDtos.DiscountCalculationRequest request) {
        return discovery.calculate(request);
    }

    @PostMapping("/internal/v1/promotion-allocation/reservations")
    public ResponseEntity<AllocationDtos.ReservationResponse> reserve(
            @Valid @RequestBody AllocationDtos.ReserveRequest request) {
        AllocationService.ReserveOutcome outcome = allocation.reserve(request);
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/internal/v1/promotion-allocation/reservations/"
                    + request.orderId() + ":" + request.promotionId())).body(outcome.response());
        }
        return ResponseEntity.ok(outcome.response());
    }

    @PostMapping("/internal/v1/promotion-allocation/confirmations")
    public AllocationDtos.ConfirmationResponse confirm(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody AllocationDtos.FinalAllocationRequest request) {
        return finalCommands.confirm(idempotencyKey, request);
    }

    @PostMapping("/internal/v1/promotion-allocation/cancellations")
    public AllocationDtos.CancelReservationResponse cancel(
            @Valid @RequestBody AllocationDtos.CancelReservationRequest request) {
        return allocation.cancel(request);
    }

    @PostMapping("/internal/v1/promotion-allocation/technical-cancellations")
    public AllocationDtos.TechnicalCancellationResponse cancelConfirmed(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody AllocationDtos.FinalAllocationRequest request) {
        return finalCommands.cancelConfirmed(idempotencyKey, request);
    }
}
