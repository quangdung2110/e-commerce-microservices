package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.PromotionCommandReceiptEntity;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.CommandOperation;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.infrastructure.persistence.CommandReceiptRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.infrastructure.redis.ReservationStore;
import com.platform.promotionservice.web.dto.AllocationDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL transaction của final command (ADR-013): receipt + guarded wallet
 * transition + durable budget cùng commit hoặc cùng rollback (PROMO-INV-013).
 * Không ghi outbox; {@code redis_finalized_at} luôn NULL khi commit (ADR-016).
 */
@Service
@RequiredArgsConstructor
public class FinalCommandTransactions {

    private final UserCouponWalletRepository wallets;
    private final CommandReceiptRepository receipts;
    private final PromotionJdbc jdbc;
    private final ObjectMapper objectMapper;
    private final PromotionProperties properties;

    /** Kết quả: receipt (mới hoặc concurrent replay) và cờ mới tạo. */
    public record Result(PromotionCommandReceiptEntity receipt, boolean created) {
    }

    @Transactional
    public Result confirm(String idempotencyKey, String requestHash, ReservationStore.ReservationPayload payload) {
        Optional<Result> committed = committedReceipt(CommandOperation.CONFIRM, payload.promotionId(),
                payload.orderId(), requestHash);
        if (committed.isPresent()) {
            return committed.get();
        }
        Instant now = jdbc.dbNow();
        UserCouponWalletEntity wallet = wallets.lockById(payload.walletId())
                .orElseThrow(() -> new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                        "Reservation references an unknown wallet"));
        // A duplicate that passed the check above may have committed while this transaction waited for the
        // wallet lock: it is a replay, not an inconsistent wallet (PROMO-INV-012).
        committed = committedReceipt(CommandOperation.CONFIRM, payload.promotionId(), payload.orderId(), requestHash);
        if (committed.isPresent()) {
            return committed.get();
        }
        if (!wallet.getUserId().equals(payload.userId()) || wallet.getPromotionId() != payload.promotionId()) {
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Reservation payload does not match the wallet identity");
        }
        // EXPIRED is accepted: Reserve required a wallet that was still valid, so an EXPIRED wallet under a live
        // reservation was normalized lazily afterwards (a query of the same user). The reservation, not the lazy
        // flag, is the authority once the Payment succeeded (ADR-017, ADR-019).
        if (wallet.getStatus() != WalletStatus.SAVED && wallet.getStatus() != WalletStatus.EXPIRED) {
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Wallet is " + wallet.getStatus() + ", expected SAVED for CONFIRM");
        }
        if (jdbc.incrementUsed(payload.promotionId(), payload.amount(), now) == 0) {
            throw new DomainException(ErrorCode.BUDGET_EXHAUSTED, "Durable budget cannot absorb this confirmation");
        }
        wallet.setStatus(WalletStatus.USED);
        wallet.setUsedOrderId(payload.orderId());
        wallet.setDiscountAmountVnd(payload.amount());
        wallet.setUsedAt(now);
        wallet.setUpdatedAt(now);

        AllocationDtos.ConfirmationResponse response = new AllocationDtos.ConfirmationResponse(
                "CONFIRM", Long.toString(payload.orderId()), Long.toString(payload.promotionId()),
                MoneyDto.vnd(payload.amount()), WalletStatus.USED, now);
        PromotionCommandReceiptEntity receipt = newReceipt(CommandOperation.CONFIRM, idempotencyKey, requestHash,
                payload.promotionId(), payload.orderId(), wallet.getId(), payload.amount(), "CONFIRMED",
                WalletStatus.USED, write(response), now);
        return insert(receipt, requestHash);
    }

    /** Legacy migration proof đã kiểm ở {@link FinalCommandService}: record ID và digest chữ thường. */
    public record MigrationProof(UUID recordId, String evidenceSha256) {
    }

    @Transactional
    public Result cancelConfirmed(String idempotencyKey, String requestHash, long promotionId, long orderId,
                                  MigrationProof proof) {
        Optional<Result> committed = committedReceipt(CommandOperation.CANCEL_CONFIRMED, promotionId, orderId,
                requestHash);
        if (committed.isPresent()) {
            return committed.get();
        }
        UserCouponWalletEntity found = wallets.findUsedByOrder(promotionId, orderId).orElse(null);
        if (found == null) {
            // A duplicate that committed in between has already cleared the wallet: replay its receipt.
            committed = committedReceipt(CommandOperation.CANCEL_CONFIRMED, promotionId, orderId, requestHash);
            if (committed.isPresent()) {
                return committed.get();
            }
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "No wallet is USED by this order for the promotion");
        }
        UserCouponWalletEntity wallet = wallets.lockById(found.getId()).orElseThrow();
        committed = committedReceipt(CommandOperation.CANCEL_CONFIRMED, promotionId, orderId, requestHash);
        if (committed.isPresent()) {
            return committed.get();
        }
        if (wallet.getStatus() != WalletStatus.USED || wallet.getUsedOrderId() == null
                || wallet.getUsedOrderId() != orderId) {
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Wallet is not USED by order " + orderId);
        }
        long amount = wallet.getDiscountAmountVnd();
        Instant now = jdbc.dbNow();
        if (jdbc.decrementUsed(promotionId, amount, now) == 0) {
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Durable used counter would underflow");
        }
        WalletStatus resulting = now.isBefore(wallet.getExpiredAt()) ? WalletStatus.SAVED : WalletStatus.EXPIRED;
        wallet.setStatus(resulting);
        wallet.setUsedOrderId(null);
        wallet.setDiscountAmountVnd(null);
        wallet.setUsedAt(null);
        wallet.setUpdatedAt(now);

        AllocationDtos.TechnicalCancellationResponse response = new AllocationDtos.TechnicalCancellationResponse(
                "CANCEL_CONFIRMED", Long.toString(orderId), Long.toString(promotionId), MoneyDto.vnd(amount),
                resulting, now);
        PromotionCommandReceiptEntity receipt = newReceipt(CommandOperation.CANCEL_CONFIRMED, idempotencyKey,
                requestHash, promotionId, orderId, wallet.getId(), amount, "CANCELLED_CONFIRMED", resulting,
                write(response), now);
        receipt.setMigrationRecordId(proof.recordId());
        receipt.setPreWatermarkEvidenceSha256(proof.evidenceSha256());
        return insert(receipt, requestHash);
    }

    private Result insert(PromotionCommandReceiptEntity receipt, String requestHash) {
        try {
            receipts.saveAndFlush(receipt);
            return new Result(receipt, true);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate đã commit cùng transition: rollback transaction này, caller replay.
            throw new ConcurrentReceiptException(receipt.getIdempotencyKey(), requestHash);
        }
    }

    /**
     * Receipt đã commit cho cùng business operation: replay nếu cùng request hash, mismatch nếu khác.
     * Gọi lại sau khi giành khoá wallet để một bản trùng vừa commit không bị coi là state không nhất quán.
     */
    private Optional<Result> committedReceipt(CommandOperation operation, long promotionId, long orderId,
                                              String requestHash) {
        return receipts.findByOperationTypeAndPromotionIdAndOrderId(operation, promotionId, orderId)
                .map(existing -> replayOrMismatch(existing, requestHash));
    }

    private static Result replayOrMismatch(PromotionCommandReceiptEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                    "A receipt for this business operation exists with a different payload");
        }
        return new Result(existing, false);
    }

    private PromotionCommandReceiptEntity newReceipt(CommandOperation operation, String key, String hash,
                                                     long promotionId, long orderId, long walletId, long amount,
                                                     String resultCode, WalletStatus resulting, String payload,
                                                     Instant now) {
        PromotionCommandReceiptEntity receipt = new PromotionCommandReceiptEntity();
        receipt.setId(UuidCreator.getTimeOrderedEpoch());
        receipt.setOperationType(operation);
        receipt.setIdempotencyKey(key);
        receipt.setRequestHash(hash);
        receipt.setPromotionId(promotionId);
        receipt.setOrderId(orderId);
        receipt.setWalletId(walletId);
        receipt.setAmountVnd(amount);
        receipt.setResultCode(resultCode);
        receipt.setResultingWalletStatus(resulting);
        receipt.setResponsePayload(payload);
        receipt.setCompletedAt(now);
        receipt.setRedisFinalizedAt(null);
        receipt.setRetainUntil(now.plus(properties.getReceipt().getRetention()));
        return receipt;
    }

    private String write(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize stored response", e);
        }
    }

    /** Unique receipt bị race: transaction đã rollback, caller đọc lại receipt và replay. */
    public static class ConcurrentReceiptException extends RuntimeException {
        private final String idempotencyKey;
        private final String requestHash;

        public ConcurrentReceiptException(String idempotencyKey, String requestHash) {
            super("concurrent receipt for " + idempotencyKey);
            this.idempotencyKey = idempotencyKey;
            this.requestHash = requestHash;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }

        public String requestHash() {
            return requestHash;
        }
    }

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
