package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CouponClaimEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.infrastructure.persistence.CouponClaimRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.infrastructure.redis.ClaimStore;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Limited flash-sale claim (WF-PROMO-003). Redis Lua với TIME + claim gate là
 * linearization point; API chỉ trả success sau PostgreSQL commit; nếu DB chưa xác
 * nhận trước deadline trả {@code 202 CLAIM_PENDING} để retry cùng user/promotion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimService {

    private final PromotionRepository promotions;
    private final CouponClaimRepository claims;
    private final UserCouponWalletRepository wallets;
    private final ClaimStore claimStore;
    private final ClaimPersistenceService persistence;
    private final RuntimeRebuildService runtimeRebuild;
    private final PromotionProperties properties;

    public sealed interface Outcome permits Committed, Pending {
    }

    public record Committed(boolean created, CheckoutDtos.ClaimVoucherResponse response) implements Outcome {
    }

    public record Pending(CheckoutDtos.ClaimPendingResponse response) implements Outcome {
    }

    public Outcome claim(UUID userId, long promotionId, long expectedRevision) {
        PromotionEntity promotion = promotions.findById(promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        if (promotion.getDistributionMode() != DistributionMode.LIMITED_CLAIM) {
            throw new DomainException(ErrorCode.DISTRIBUTION_MODE_MISMATCH,
                    "Promotion is not a LIMITED_CLAIM voucher");
        }

        // Preflight durable claim/wallet trước Lua: durable hit replay success.
        Optional<UserCouponWalletEntity> durable = wallets.findByUserIdAndPromotionId(userId, promotionId);
        if (durable.isPresent()) {
            return replay(durable.get());
        }

        // Preflight pending evidence: crash window của request trước → resume persistence.
        Optional<Long> claimed = claimStore.claimedClaimId(promotionId, userId);
        if (claimed.isPresent()) {
            Optional<ClaimStore.PendingClaim> pending = claimStore.pending(promotionId, claimed.get());
            if (pending.isPresent()) {
                return persistOrPending(promotionId, pending.get());
            }
        }

        ClaimStore.AcquireResult acquired = acquire(userId, promotionId, expectedRevision);
        return switch (acquired.code()) {
            case ACQUIRED -> persistOrPending(promotionId, acquired.pending());
            case ALREADY_CLAIMED -> {
                if (acquired.pending() != null) {
                    yield persistOrPending(promotionId, acquired.pending());
                }
                // Pending bị ack chỉ sau khi PostgreSQL commit: request đồng thời của cùng user có thể đã commit và
                // dọn pending giữa preflight wallet ở trên và Lua acquire, nên đọc lại wallet trước khi từ chối.
                Optional<UserCouponWalletEntity> committed = wallets.findByUserIdAndPromotionId(userId, promotionId);
                if (committed.isPresent()) {
                    yield replay(committed.get());
                }
                throw new DomainException(ErrorCode.ALREADY_CLAIMED, "User already claimed this promotion");
            }
            case OUT_OF_STOCK -> throw new DomainException(ErrorCode.OUT_OF_STOCK, "Claim pool is exhausted");
            case GATE_STALE -> throw new DomainException(ErrorCode.CLAIM_GATE_STALE,
                    "Expected campaign revision " + expectedRevision + " does not match the current claim gate");
            case GATE_DISABLED, OUT_OF_WINDOW -> throw new DomainException(ErrorCode.CAMPAIGN_NOT_ACTIVE,
                    "Claim window is closed");
            case GATE_MISSING -> throw new DomainException(ErrorCode.RUNTIME_NOT_READY,
                    "Claim runtime is being rebuilt; retry shortly");
        };
    }

    private ClaimStore.AcquireResult acquire(UUID userId, long promotionId, long expectedRevision) {
        long staleAfterMs = properties.getClaim().getPendingStaleAfter().toMillis();
        ClaimStore.AcquireResult result = claimStore.acquire(promotionId, userId, expectedRevision, staleAfterMs);
        if (result.code() == ClaimStore.AcquireCode.GATE_MISSING) {
            // Redis loss: rebuild runtime claim fields/pool từ PostgreSQL rồi thử lại một lần.
            runtimeRebuild.rebuildPromotion(promotionId);
            result = claimStore.acquire(promotionId, userId, expectedRevision, staleAfterMs);
        }
        return result;
    }

    /** Persist ngay; lỗi hạ tầng DB → 202 CLAIM_PENDING, pending giữ nguyên cho sweeper. */
    private Outcome persistOrPending(long promotionId, ClaimStore.PendingClaim pending) {
        ClaimPersistenceService.Persisted persisted;
        try {
            persisted = persistence.persist(promotionId, pending);
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | CannotCreateTransactionException e) {
            log.warn("Claim persistence deferred for promotion {} claim {}: {}", promotionId, pending.claimId(),
                    e.getMessage());
            return new Pending(CheckoutDtos.ClaimPendingResponse.of(Long.toString(promotionId),
                    Long.toString(pending.claimId())));
        } catch (DomainException e) {
            if (e.errorCode() == ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT) {
                // Pending evidence đã chết (claim thuộc user khác): dọn để không retry vô hạn.
                claimStore.ack(promotionId, pending.claimId());
            }
            throw e;
        }
        claimStore.ack(promotionId, pending.claimId());
        return new Committed(persisted.created(), toResponse(persisted.wallet(), persisted.claim()));
    }

    private Outcome replay(UserCouponWalletEntity wallet) {
        CouponClaimEntity claim = wallet.getCouponClaimId() == null ? null
                : claims.findById(wallet.getCouponClaimId()).orElse(null);
        return new Committed(false, toResponse(wallet, claim));
    }

    private static CheckoutDtos.ClaimVoucherResponse toResponse(UserCouponWalletEntity wallet, CouponClaimEntity claim) {
        Instant acquiredAt = claim != null && claim.getClaimedAt() != null ? claim.getClaimedAt() : wallet.getSavedAt();
        long revision = claim != null && claim.getClaimedCampaignRevision() != null ? claim.getClaimedCampaignRevision() : 1;
        return new CheckoutDtos.ClaimVoucherResponse(
                claim == null ? null : Long.toString(claim.getId()),
                acquiredAt, revision, WalletDtos.WalletVoucher.from(wallet));
    }

    /** Sweeper: persist lại pending quá ngưỡng bằng cùng guarded path; cleanup chỉ sau commit. */
    public int sweepPending(long promotionId, Instant now, int limit) {
        int persisted = 0;
        for (Long claimId : claimStore.duePending(promotionId, now.toEpochMilli(), limit)) {
            Optional<ClaimStore.PendingClaim> pending = claimStore.pending(promotionId, claimId);
            if (pending.isEmpty()) {
                claimStore.ack(promotionId, claimId);
                continue;
            }
            try {
                persistence.persist(promotionId, pending.get());
                claimStore.ack(promotionId, claimId);
                persisted++;
            } catch (DomainException e) {
                if (e.errorCode() == ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT) {
                    log.error("Pending claim {} of promotion {} is inconsistent: {}", claimId, promotionId, e.getMessage());
                    claimStore.ack(promotionId, claimId);
                } else {
                    log.warn("Pending claim {} of promotion {} rejected: {}", claimId, promotionId, e.getMessage());
                }
            } catch (DataAccessException e) {
                log.warn("Pending claim {} of promotion {} deferred: {}", claimId, promotionId, e.getMessage());
            }
        }
        return persisted;
    }
}
