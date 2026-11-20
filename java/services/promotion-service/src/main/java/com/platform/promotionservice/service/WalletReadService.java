package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MoneyDto;
import com.platform.promotionservice.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Own wallet history, independent of cart/Redis/campaign phase and checkout eligibility. */
@Service
@RequiredArgsConstructor
public class WalletReadService {
    private final UserCouponWalletRepository wallets;
    private final PromotionJdbc jdbc;
    private final PromotionSnapshotService snapshots;

    @Transactional(readOnly = true)
    public WalletDtos.WalletPage list(UUID userId, WalletStatus status, String cursor, Integer limit) {
        int size = AdminSupport.limitOf(limit);
        long beforeId = decode(cursor, userId, status);
        var now = jdbc.dbNow();
        var rows = wallets.readWallet(userId, status == null ? null : status.name(), now, beforeId,
                PageRequest.of(0, size + 1));
        var items = rows.stream().limit(size).map(row -> {
            var w = row.getWallet();
            var p = row.getPromotion();
            var effective = w.getStatus() == WalletStatus.SAVED && w.isExpiredAt(now)
                    ? WalletStatus.EXPIRED : w.getStatus();
            return new WalletDtos.WalletEntry(WalletDtos.WalletVoucher.from(w), effective, p.getCode(),
                    DiscountDto.fromStorage(p.getDiscountType(), p.getDiscountValue(), p.getMaxDiscountVnd()),
                    MoneyDto.vnd(p.getMinOrderAmountVnd()),
                    WalletTerms.describe(snapshots.parseRule(p.getConditionsRule())), p.isEnabled());
        }).toList();
        String next = rows.size() > size
                ? encode(userId, status, rows.get(size - 1).getWallet().getId()) : null;
        return new WalletDtos.WalletPage(items, next, now);
    }

    private static String scope(UUID userId, WalletStatus status) {
        return "wallet-v1:" + userId + ":" + (status == null ? "ALL" : status.name()) + ":";
    }

    private static String encode(UUID userId, WalletStatus status, long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope(userId, status) + id).getBytes(StandardCharsets.UTF_8));
    }

    private static long decode(String cursor, UUID userId, WalletStatus status) {
        if (cursor == null) return Long.MAX_VALUE;
        try {
            if (cursor.isBlank() || cursor.length() > 256) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = scope(userId, status);
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
            String value = decoded.substring(prefix.length());
            if (!value.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
            return Long.parseLong(value);
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.INVALID_CURSOR, "Cursor does not match this wallet and filter");
        }
    }
}
