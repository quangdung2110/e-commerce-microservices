package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.PromotionCommandReceiptEntity;
import com.platform.promotionservice.domain.enums.CommandOperation;
import com.platform.promotionservice.infrastructure.persistence.CommandReceiptRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.redis.ReservationStore;
import com.platform.promotionservice.web.dto.AllocationDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * CONFIRM và technical CANCEL_CONFIRMED (WF-PROMO-005/006, ADR-013/016): preflight
 * receipt → Redis Confirm-Prepare fence (chỉ CONFIRM) → PostgreSQL-first commit →
 * Redis finalize/repair sau commit → set {@code redis_finalized_at}. Crash ở bất kỳ
 * bước nào đều có replay/receipt-driven finalizer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinalCommandService {

    public static final String CONFIRM_KEY_PREFIX = "REDEEM_FINAL:";
    public static final String CANCEL_KEY_PREFIX = "CANCEL_FINAL:";

    private final CommandReceiptRepository receipts;
    private final FinalCommandTransactions transactions;
    private final ReservationStore reservations;
    private final RuntimeRebuildService runtimeRebuild;
    private final PromotionJdbc jdbc;
    private final ObjectMapper objectMapper;
    private final PromotionProperties properties;
    private final Clock clock;

    public AllocationDtos.ConfirmationResponse confirm(String idempotencyKey, AllocationDtos.FinalAllocationRequest request) {
        long orderId = Ids.parse(request.orderId(), "order_id");
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        long expected = request.expectedDiscount().requireVnd("expected_discount", 0);
        requireKey(idempotencyKey, CONFIRM_KEY_PREFIX, orderId, promotionId);
        String requestHash = requestHash(CommandOperation.CONFIRM, orderId, promotionId, expected);

        Optional<PromotionCommandReceiptEntity> durable = preflight(CommandOperation.CONFIRM, idempotencyKey,
                promotionId, orderId, requestHash);
        if (durable.isPresent()) {
            kickFinalizer(durable.get());
            return read(durable.get(), AllocationDtos.ConfirmationResponse.class);
        }

        long graceMs = properties.getReservation().getCleanupGrace().toMillis();
        long ttlMs = properties.getReservation().getBusinessTtl().toMillis() + graceMs;
        long newExpiry = clock.millis() + graceMs;
        ReservationStore.PrepareResult prepared = reservations.prepare(promotionId, orderId, newExpiry, ttlMs);
        if (prepared.code() == ReservationStore.PrepareCode.MISSING) {
            // Đóng race với request khác vừa commit/finalize trước khi kết luận reservation mất.
            Optional<PromotionCommandReceiptEntity> recheck = preflight(CommandOperation.CONFIRM, idempotencyKey,
                    promotionId, orderId, requestHash);
            if (recheck.isPresent()) {
                kickFinalizer(recheck.get());
                return read(recheck.get(), AllocationDtos.ConfirmationResponse.class);
            }
            throw new DomainException(ErrorCode.RESERVATION_NOT_FOUND,
                    "No reservation for this order; apply late-payment policy");
        }

        FinalCommandTransactions.Result result;
        try {
            result = transactions.confirm(idempotencyKey, requestHash, prepared.payload());
        } catch (FinalCommandTransactions.ConcurrentReceiptException e) {
            result = new FinalCommandTransactions.Result(preflight(CommandOperation.CONFIRM, idempotencyKey,
                    promotionId, orderId, requestHash).orElseThrow(), false);
        }
        kickFinalizer(result.receipt());
        return read(result.receipt(), AllocationDtos.ConfirmationResponse.class);
    }

    /**
     * Legacy technical cancellation (deprecated, ADR-013/017/019). Chỉ {@code promotion-legacy-migrator} tới được
     * route (allowlist ở {@code TrustedIdentityFilter}); ở đây kiểm proof bất biến: header
     * {@code X-Legacy-Migration-Proof} phải là UUID bằng {@code migration_record_id} trong body, nếu không
     * {@code 403 FORBIDDEN} trước mọi thao tác đọc/ghi. Record ID và digest nằm trong request hash nên replay với
     * proof khác là {@code IDEMPOTENCY_PAYLOAD_MISMATCH}, và được lưu trên receipt.
     */
    public AllocationDtos.TechnicalCancellationResponse cancelConfirmed(
            String idempotencyKey, String proofHeader, AllocationDtos.LegacyTechnicalCancellationRequest request) {
        FinalCommandTransactions.MigrationProof proof = requireProof(proofHeader, request);
        long orderId = Ids.parse(request.orderId(), "order_id");
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        long expected = request.expectedDiscount().requireVnd("expected_discount", 0);
        requireKey(idempotencyKey, CANCEL_KEY_PREFIX, orderId, promotionId);
        String requestHash = legacyRequestHash(orderId, promotionId, expected, proof);

        Optional<PromotionCommandReceiptEntity> durable = preflight(CommandOperation.CANCEL_CONFIRMED, idempotencyKey,
                promotionId, orderId, requestHash);
        if (durable.isPresent()) {
            kickFinalizer(durable.get());
            return read(durable.get(), AllocationDtos.TechnicalCancellationResponse.class);
        }
        FinalCommandTransactions.Result result;
        try {
            result = transactions.cancelConfirmed(idempotencyKey, requestHash, promotionId, orderId, proof);
        } catch (FinalCommandTransactions.ConcurrentReceiptException e) {
            result = new FinalCommandTransactions.Result(preflight(CommandOperation.CANCEL_CONFIRMED, idempotencyKey,
                    promotionId, orderId, requestHash).orElseThrow(), false);
        }
        kickFinalizer(result.receipt());
        return read(result.receipt(), AllocationDtos.TechnicalCancellationResponse.class);
    }

    /**
     * Lookup receipt theo key rồi theo business identity: cùng key + cùng hash → stored
     * response; khác hash → IDEMPOTENCY_PAYLOAD_MISMATCH; key khác nhưng cùng business
     * operation → replay hoặc conflict, không tạo transition thứ hai.
     */
    private Optional<PromotionCommandReceiptEntity> preflight(CommandOperation operation, String key,
                                                               long promotionId, long orderId, String requestHash) {
        Optional<PromotionCommandReceiptEntity> byKey = receipts.findByIdempotencyKey(key);
        if (byKey.isPresent()) {
            return Optional.of(verify(byKey.get(), operation, promotionId, orderId, requestHash));
        }
        Optional<PromotionCommandReceiptEntity> byBusiness =
                receipts.findByOperationTypeAndPromotionIdAndOrderId(operation, promotionId, orderId);
        return byBusiness.map(receipt -> verify(receipt, operation, promotionId, orderId, requestHash));
    }

    private static PromotionCommandReceiptEntity verify(PromotionCommandReceiptEntity receipt, CommandOperation operation,
                                                        long promotionId, long orderId, String requestHash) {
        if (receipt.getOperationType() != operation || receipt.getPromotionId() != promotionId
                || receipt.getOrderId() != orderId || !receipt.getRequestHash().equals(requestHash)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                    "Idempotency key was already used with a different payload");
        }
        return receipt;
    }

    /**
     * Inline finalize/repair sau commit; lỗi Redis chỉ log, receipt còn
     * {@code redis_finalized_at IS NULL} để finalizer worker chạy lại.
     */
    public void kickFinalizer(PromotionCommandReceiptEntity receipt) {
        if (receipt.getRedisFinalizedAt() != null) {
            return;
        }
        try {
            if (finalizeRedis(receipt)) {
                Instant now = clock.instant();
                jdbc.markReceiptFinalized(receipt.getId(), now);
                receipt.setRedisFinalizedAt(now);
            }
        } catch (RuntimeException e) {
            log.warn("Redis finalize deferred for receipt {} ({}): {}", receipt.getId(),
                    receipt.getOperationType(), e.getMessage());
        }
    }

    /** true khi Redis đã hội tụ (finalize matching payload hoặc reconcile từ DB xong). */
    public boolean finalizeRedis(PromotionCommandReceiptEntity receipt) {
        long promotionId = receipt.getPromotionId();
        if (receipt.getOperationType() == CommandOperation.CONFIRM) {
            ReservationStore.FinalizeCode code = reservations.finalize(promotionId, receipt.getOrderId());
            return switch (code) {
                case FINALIZED -> true;
                // Payload đã mất/đã finalize hoặc counter drift: không increment mù, reconcile từ DB.
                case MISSING, DRIFT -> runtimeRebuild.reconcile(promotionId);
            };
        }
        return runtimeRebuild.reconcile(promotionId);
    }

    static String requestHash(CommandOperation operation, long orderId, long promotionId, long expectedAmount) {
        return AllocationService.sha256(operation.name() + "|" + orderId + "|" + promotionId + "|" + expectedAmount);
    }

    /** Canonical hash của legacy cancel: gồm cả migration record ID và digest bằng chứng (data.md §3.8). */
    static String legacyRequestHash(long orderId, long promotionId, long expectedAmount,
                                    FinalCommandTransactions.MigrationProof proof) {
        return AllocationService.sha256(CommandOperation.CANCEL_CONFIRMED.name() + "|" + orderId + "|" + promotionId
                + "|" + expectedAmount + "|" + proof.recordId() + "|" + proof.evidenceSha256());
    }

    /** Header thiếu, không phải UUID hoặc khác {@code migration_record_id} của body → 403 (capability bị từ chối). */
    private static FinalCommandTransactions.MigrationProof requireProof(
            String proofHeader, AllocationDtos.LegacyTechnicalCancellationRequest request) {
        UUID header = null;
        if (proofHeader != null && !proofHeader.isBlank()) {
            try {
                header = UUID.fromString(proofHeader.trim());
            } catch (IllegalArgumentException e) {
                header = null;
            }
        }
        if (header == null || !header.equals(request.migrationRecordId())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "X-Legacy-Migration-Proof must carry the migration_record_id of the request body");
        }
        return new FinalCommandTransactions.MigrationProof(header,
                request.preFinancialWatermarkEvidenceSha256().toLowerCase(Locale.ROOT));
    }

    private static void requireKey(String key, String prefix, long orderId, long promotionId) {
        String expected = prefix + orderId + ":" + promotionId;
        if (key == null || !key.equals(expected)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be " + prefix + "{order_id}:{promotion_id} matching the body");
        }
    }

    private <T> T read(PromotionCommandReceiptEntity receipt, Class<T> type) {
        try {
            return objectMapper.readValue(receipt.getResponsePayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("stored response of receipt " + receipt.getId() + " is unreadable", e);
        }
    }
}
