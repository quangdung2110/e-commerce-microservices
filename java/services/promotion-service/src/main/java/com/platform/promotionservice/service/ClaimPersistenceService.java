package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.CouponClaimEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.ClaimStatus;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.domain.pricing.ValidityPolicy;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.CouponClaimRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.infrastructure.redis.ClaimStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Guarded PostgreSQL persistence dùng chung cho API inline và pending sweeper
 * (WF-PROMO-003 bước 5–6): claim AVAILABLE→CLAIMED, wallet SAVED với expired_at
 * snapshot theo acquisition time, claimed counter — một transaction, không outbox.
 * Persistence dùng acquisition evidence, không so revision-at-acquisition với
 * current revision và không dùng processing time để bác claim đã linearize đúng hạn.
 */
@Service
@RequiredArgsConstructor
public class ClaimPersistenceService {

    private final PromotionRepository promotions;
    private final CampaignRepository campaigns;
    private final CouponClaimRepository claims;
    private final UserCouponWalletRepository wallets;
    private final PromotionJdbc jdbc;

    public record Persisted(UserCouponWalletEntity wallet, CouponClaimEntity claim, boolean created) {
    }

    @Transactional
    public Persisted persist(long promotionId, ClaimStore.PendingClaim pending) {
        PromotionEntity promotion = promotions.findById(promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        CampaignEntity campaign = campaigns.findById(promotion.getCampaignId()).orElseThrow();
        Instant acquiredAt = Instant.ofEpochMilli(pending.acquiredAtMs());

        Optional<UserCouponWalletEntity> existingWallet = wallets.lockByUserAndPromotion(pending.userId(), promotionId);
        if (existingWallet.isPresent()) {
            CouponClaimEntity claim = claims.findById(existingWallet.get().getCouponClaimId() == null
                    ? pending.claimId() : existingWallet.get().getCouponClaimId()).orElseThrow();
            return new Persisted(existingWallet.get(), claim, false);
        }

        int rows = jdbc.claimGuarded(pending.claimId(), promotionId, pending.userId(), acquiredAt,
                pending.campaignRevision());
        if (rows == 0) {
            CouponClaimEntity claim = claims.findById(pending.claimId())
                    .orElseThrow(() -> new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                            "Pending claim references an unknown claim row"));
            if (claim.getStatus() == ClaimStatus.CLAIMED && pending.userId().equals(claim.getOwnerId())) {
                // Claim đã CLAIMED bởi chính user nhưng wallet chưa có: chỉ xảy ra khi wallet bị xóa thủ công.
                throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                        "Claim is owned by the user but the wallet row is missing");
            }
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Claim " + pending.claimId() + " is no longer AVAILABLE for this user");
        }
        if (jdbc.incrementClaimed(promotionId, acquiredAt) == 0) {
            throw new DomainException(ErrorCode.OUT_OF_STOCK, "Promotion claim quantity is exhausted");
        }

        UserCouponWalletEntity wallet = new UserCouponWalletEntity();
        wallet.setUserId(pending.userId());
        wallet.setPromotionId(promotionId);
        wallet.setCouponClaimId(pending.claimId());
        wallet.setStatus(WalletStatus.SAVED);
        wallet.setSavedAt(acquiredAt);
        wallet.setExpiredAt(ValidityPolicy.expiryFor(promotion, campaign, acquiredAt));
        wallet.setUpdatedAt(acquiredAt);
        try {
            wallets.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCode.FINAL_COMMAND_STATE_INCONSISTENT,
                    "Wallet insert conflicts with an existing row for the user");
        }
        CouponClaimEntity claim = claims.findById(pending.claimId()).orElseThrow();
        return new Persisted(wallet, claim, true);
    }
}
