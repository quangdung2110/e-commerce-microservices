package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.CommandOperation;
import com.platform.promotionservice.infrastructure.persistence.CommandReceiptRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.infrastructure.redis.ReservationStore;
import com.platform.promotionservice.web.dto.AllocationDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * WF-PROMO-005 TRY và CANCEL pre-payment: Redis runtime hold, không persist RESERVED,
 * không đổi wallet. Reserve all-or-nothing; replay cùng payload; khác payload
 * trả IDEMPOTENCY_PAYLOAD_MISMATCH (PROMO-BR-005).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllocationService {

    private final PromotionSnapshotService snapshots;
    private final VoucherEvaluationService evaluator;
    private final UserCouponWalletRepository wallets;
    private final CommandReceiptRepository receipts;
    private final ReservationStore reservations;
    private final RuntimeRebuildService runtimeRebuild;
    private final PromotionProperties properties;
    private final Clock clock;

    public record ReserveOutcome(boolean created, AllocationDtos.ReservationResponse response) {
    }

    public ReserveOutcome reserve(AllocationDtos.ReserveRequest request) {
        long orderId = Ids.parse(request.orderId(), "order_id");
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        long expected = request.expectedDiscount().requireVnd("expected_discount", 0);
        Instant now = clock.instant();

        PromotionView view = snapshots.find(promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        UserCouponWalletEntity wallet = wallets.findByUserIdAndPromotionId(request.userId(), promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.VOUCHER_NOT_OWNED, "Voucher is not in the wallet"));
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), request.userId(), request.userTier());
        VoucherEvaluationService.Evaluation evaluation = evaluator.evaluate(view, wallet, context, now, false);
        if (!evaluation.eligible()) {
            throw switch (evaluation.reason()) {
                case ALREADY_USED_VOUCHER -> new DomainException(ErrorCode.ALREADY_USED_VOUCHER, "Voucher already used");
                case VOUCHER_EXPIRED -> new DomainException(ErrorCode.VOUCHER_EXPIRED, "Voucher expired");
                case PROMOTION_DISABLED, CAMPAIGN_NOT_ACTIVE ->
                        new DomainException(ErrorCode.CAMPAIGN_NOT_ACTIVE, "Promotion is not available");
                default -> new DomainException(ErrorCode.CONDITIONS_NOT_MET,
                        "Voucher conditions not met: " + evaluation.reason());
            };
        }
        long amount = evaluation.discountVnd();
        if (amount != expected) {
            throw new DomainException(ErrorCode.EXPECTED_DISCOUNT_MISMATCH,
                    "Evaluated discount " + amount + " differs from expected " + expected);
        }

        long ttlMs = properties.getReservation().getBusinessTtl().toMillis();
        long graceMs = properties.getReservation().getCleanupGrace().toMillis();
        ReservationStore.ReservationPayload payload = new ReservationStore.ReservationPayload(
                orderId, promotionId, request.userId(), wallet.getId(), amount, ReservationStore.STATE_RESERVED,
                now.toEpochMilli(), now.toEpochMilli() + ttlMs,
                payloadHash(orderId, promotionId, request.userId().toString(), amount));

        ReservationStore.ReserveResult result = reservations.reserve(payload, ttlMs + graceMs);
        if (result.code() == ReservationStore.ReserveCode.RUNTIME_MISSING) {
            runtimeRebuild.rebuildPromotion(promotionId);
            result = reservations.reserve(payload, ttlMs + graceMs);
        }
        return switch (result.code()) {
            case RESERVED -> new ReserveOutcome(true, toResponse(result.payload()));
            case REPLAY -> new ReserveOutcome(false, toResponse(result.payload()));
            case PAYLOAD_MISMATCH -> throw new DomainException(ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                    "Reservation for this order exists with a different payload");
            case BUDGET_EXHAUSTED -> throw new DomainException(ErrorCode.BUDGET_EXHAUSTED,
                    "Promotion quota or budget is exhausted");
            case RUNTIME_MISSING -> throw new DomainException(ErrorCode.RUNTIME_NOT_READY,
                    "Promotion runtime is being rebuilt; retry shortly");
        };
    }

    /** CANCEL trước payment: chỉ release RESERVED; CONFIRMING không release mù. */
    public AllocationDtos.CancelReservationResponse cancel(AllocationDtos.CancelReservationRequest request) {
        long orderId = Ids.parse(request.orderId(), "order_id");
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        ReservationStore.CancelCode code = reservations.cancel(promotionId, orderId, false);
        AllocationDtos.CancelOutcome outcome = switch (code) {
            case RELEASED -> AllocationDtos.CancelOutcome.RELEASED;
            case DRIFT -> {
                runtimeRebuild.reconcile(promotionId);
                yield AllocationDtos.CancelOutcome.RELEASED;
            }
            case CONFIRMING -> {
                // Caller phải đọc durable confirm outcome; nếu đã có receipt thì không còn reservation để nhả.
                boolean confirmed = receipts.findByOperationTypeAndPromotionIdAndOrderId(
                        CommandOperation.CONFIRM, promotionId, orderId).isPresent();
                yield confirmed ? AllocationDtos.CancelOutcome.NO_RESERVATION : AllocationDtos.CancelOutcome.REPAIR_REQUIRED;
            }
            case NO_RESERVATION -> {
                boolean indexed = reservations.indexedOrders(promotionId).contains(orderId);
                if (indexed) {
                    reservations.removeIndex(promotionId, orderId);
                    runtimeRebuild.reconcile(promotionId);
                    yield AllocationDtos.CancelOutcome.REPAIR_REQUIRED;
                }
                yield AllocationDtos.CancelOutcome.NO_RESERVATION;
            }
        };
        return new AllocationDtos.CancelReservationResponse(request.orderId(), request.promotionId(), outcome);
    }

    static String payloadHash(long orderId, long promotionId, String userId, long amount) {
        return sha256("RESERVE|" + orderId + "|" + promotionId + "|" + userId + "|" + amount);
    }

    static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AllocationDtos.ReservationResponse toResponse(ReservationStore.ReservationPayload payload) {
        return new AllocationDtos.ReservationResponse(
                Long.toString(payload.orderId()), Long.toString(payload.promotionId()),
                ReservationStore.STATE_RESERVED, MoneyDto.vnd(payload.amount()),
                Instant.ofEpochMilli(payload.reservedAtMs()), Instant.ofEpochMilli(payload.expiresAtMs()));
    }
}
