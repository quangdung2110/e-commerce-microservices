package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.WalletStatus;

import java.time.Instant;
import java.util.UUID;

/** WalletVoucher trên wire (openapi WalletVoucher). */
public final class WalletDtos {

    private WalletDtos() {
    }

    public record WalletVoucher(
            @JsonProperty("wallet_id") String walletId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("promotion_id") String promotionId,
            @JsonProperty("claim_id") String claimId,
            WalletStatus status,
            @JsonProperty("saved_at") Instant savedAt,
            @JsonProperty("expired_at") Instant expiredAt,
            @JsonProperty("used_order_id") String usedOrderId,
            @JsonProperty("discount_amount") MoneyDto discountAmount,
            @JsonProperty("used_at") Instant usedAt,
            @JsonProperty("updated_at") Instant updatedAt) {

        public static WalletVoucher from(UserCouponWalletEntity wallet) {
            return new WalletVoucher(
                    Long.toString(wallet.getId()),
                    wallet.getUserId(),
                    Long.toString(wallet.getPromotionId()),
                    Ids.opaque(wallet.getCouponClaimId()),
                    wallet.getStatus(),
                    wallet.getSavedAt(),
                    wallet.getExpiredAt(),
                    Ids.opaque(wallet.getUsedOrderId()),
                    MoneyDto.ofNullable(wallet.getDiscountAmountVnd()),
                    wallet.getUsedAt(),
                    wallet.getUpdatedAt());
        }
    }
}
