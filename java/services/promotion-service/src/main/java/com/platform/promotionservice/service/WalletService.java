package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.domain.pricing.ValidityPolicy;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Public save và direct grant (WF-PROMO-003 §Public save/direct grant). Phase kiểm
 * bằng PostgreSQL clock trong cùng transaction với wallet insert; existing SAVED còn hạn
 * là idempotent replay không kéo dài expiry; không có default 30 ngày.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final PromotionRepository promotions;
    private final CampaignRepository campaigns;
    private final UserCouponWalletRepository wallets;
    private final PromotionJdbc jdbc;
    private final PromotionSnapshotService snapshots;
    private final VoucherEvaluationService evaluator;

    public record SaveOutcome(UserCouponWalletEntity wallet, boolean created) {
    }

    @Transactional
    public SaveOutcome savePublic(UUID userId, long promotionId) {
        PromotionEntity promotion = promotions.findById(promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        if (promotion.getDistributionMode() != DistributionMode.PUBLIC_SAVE) {
            throw new DomainException(ErrorCode.DISTRIBUTION_MODE_MISMATCH,
                    "Only PUBLIC_SAVE vouchers can be saved from discovery");
        }
        CampaignEntity campaign = campaigns.findById(promotion.getCampaignId()).orElseThrow();
        Instant now = jdbc.dbNow();
        if (!campaign.isActiveAt(now) || !promotion.isEnabled()) {
            throw new DomainException(ErrorCode.CAMPAIGN_NOT_ACTIVE, "Campaign is not active for saving");
        }
        return acquireWallet(userId, promotion, campaign, now, true);
    }

    /** Admin DIRECT_GRANT: campaign PUBLISHED và now < end_time, promotion enabled. */
    @Transactional
    public SaveOutcome grant(UUID userId, long promotionId) {
        PromotionEntity promotion = promotions.findById(promotionId)
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        if (promotion.getDistributionMode() != DistributionMode.DIRECT_GRANT) {
            throw new DomainException(ErrorCode.DISTRIBUTION_MODE_MISMATCH,
                    "Only DIRECT_GRANT vouchers can be granted");
        }
        CampaignEntity campaign = campaigns.findById(promotion.getCampaignId()).orElseThrow();
        Instant now = jdbc.dbNow();
        if (campaign.getPublicationStatus() != PublicationStatus.PUBLISHED || !now.isBefore(campaign.getEndTime())
                || !promotion.isEnabled()) {
            throw new DomainException(ErrorCode.CAMPAIGN_NOT_ACTIVE, "Campaign does not allow direct grant");
        }
        return acquireWallet(userId, promotion, campaign, now, true);
    }

    /**
     * Guarded wallet acquisition dùng chung: replay SAVED còn hạn, từ chối USED/EXPIRED,
     * tăng claimed_quantity nếu total_claim_quantity hữu hạn.
     */
    SaveOutcome acquireWallet(UUID userId, PromotionEntity promotion, CampaignEntity campaign, Instant now,
                              boolean countClaimed) {
        Optional<UserCouponWalletEntity> existing = wallets.lockByUserAndPromotion(userId, promotion.getId());
        if (existing.isPresent()) {
            return replay(existing.get(), now);
        }
        if (countClaimed && jdbc.incrementClaimed(promotion.getId(), now) == 0) {
            throw new DomainException(ErrorCode.OUT_OF_STOCK, "Promotion claim quantity is exhausted");
        }
        UserCouponWalletEntity wallet = new UserCouponWalletEntity();
        wallet.setUserId(userId);
        wallet.setPromotionId(promotion.getId());
        wallet.setStatus(WalletStatus.SAVED);
        wallet.setSavedAt(now);
        wallet.setExpiredAt(ValidityPolicy.expiryFor(promotion, campaign, now));
        wallet.setUpdatedAt(now);
        try {
            wallets.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException e) {
            // Race với request song song của cùng user: unique (user_id,promotion_id) chọn đúng một.
            UserCouponWalletEntity winner = wallets.findByUserIdAndPromotionId(userId, promotion.getId())
                    .orElseThrow(() -> e);
            return replay(winner, now);
        }
        return new SaveOutcome(wallet, true);
    }

    private SaveOutcome replay(UserCouponWalletEntity wallet, Instant now) {
        switch (wallet.getStatus()) {
            case USED -> throw new DomainException(ErrorCode.ALREADY_USED_VOUCHER, "Voucher already used");
            case EXPIRED -> throw new DomainException(ErrorCode.VOUCHER_EXPIRED, "Voucher expired");
            case SAVED -> {
                if (wallet.isExpiredAt(now)) {
                    wallet.setStatus(WalletStatus.EXPIRED);
                    wallet.setUpdatedAt(now);
                    throw new DomainException(ErrorCode.VOUCHER_EXPIRED, "Voucher expired");
                }
                return new SaveOutcome(wallet, false);
            }
            default -> throw new IllegalStateException("unknown wallet status");
        }
    }

    @Transactional(readOnly = true)
    public CheckoutDtos.VoucherEvaluation evaluateWallet(UserCouponWalletEntity wallet, UUID userId,
                                                         CheckoutDtos.CheckoutContext context, Instant now) {
        PromotionView view = snapshots.find(wallet.getPromotionId())
                .orElseThrow(() -> new DomainException(ErrorCode.PROMOTION_NOT_FOUND, "Promotion not found"));
        RuleFactsBuilder.Context facts = RuleFactsBuilder.build(context, userId, null);
        return evaluator.toDto(evaluator.evaluate(view, wallet, facts, now, true), VoucherSource.WALLET, wallet.getId());
    }

    public WalletDtos.WalletVoucher toDto(UserCouponWalletEntity wallet) {
        return WalletDtos.WalletVoucher.from(wallet);
    }
}
