package com.platform.promotionservice.integration;

import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class WalletReadIntegrationTest extends AbstractPromotionIntegrationTest {
    @Autowired MockMvc mvc;
    private static final String URL = "/api/v1/me/promotion-vouchers";
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000bb01");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000bb02");

    private long wallet(UUID buyer, String status, String expiry) {
        var c = givenActiveWindowCampaign(Duration.ofHours(1));
        var p = givenPromotion(c, DistributionMode.PUBLIC_SAVE, null);
        return jdbcTemplate.queryForObject("""
                INSERT INTO user_coupon_wallets(user_id,promotion_id,status,saved_at,expired_at,
                    used_order_id,discount_amount_vnd,used_at)
                VALUES (?, ?, ?, now()-interval '2 days', now() + CAST(? AS interval),
                    CASE WHEN ?='USED' THEN 8811 ELSE NULL END,
                    CASE WHEN ?='USED' THEN 10000 ELSE NULL END,
                    CASE WHEN ?='USED' THEN now()-interval '1 day' ELSE NULL END) RETURNING id
                """, Long.class, buyer, p.getId(), status, expiry, status, status, status);
    }

    @Test void readsOnlyOwnerWithTermsAndNoStoreIncludingNonActiveCampaign() throws Exception {
        long own = wallet(BUYER, "SAVED", "2 days");
        wallet(OTHER, "SAVED", "2 days");
        // Draft/cancelled campaign is not an ownership revocation; read does not evaluate.
        jdbcTemplate.update("UPDATE campaigns SET publication_status='CANCELLED'");
        mvc.perform(get(URL).header("X-User-Id", BUYER).param("user_id", OTHER.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].wallet.wallet_id").value(Long.toString(own)))
                .andExpect(jsonPath("$.items[0].wallet.user_id").value(BUYER.toString()))
                .andExpect(jsonPath("$.items[0].effective_status").value("SAVED"))
                .andExpect(jsonPath("$.items[0].discount.value_basis_points").value(1000))
                .andExpect(jsonPath("$.items[0].min_order_amount.amount_minor").value(100000))
                .andExpect(jsonPath("$.items[0].conditions_summary").isString())
                .andExpect(jsonPath("$.next_cursor").isEmpty()).andExpect(jsonPath("$.as_of").isString());
    }

    @Test void effectiveExpiryDoesNotMutateStoredStateAndUsedRemainsUsed() throws Exception {
        long expired = wallet(BUYER, "SAVED", "-1 second");
        wallet(BUYER, "USED", "-1 day");
        wallet(BUYER, "SAVED", "2 days");
        mvc.perform(get(URL).header("X-User-Id", BUYER).param("status", "EXPIRED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].effective_status").value("EXPIRED"))
                .andExpect(jsonPath("$.items[0].wallet.status").value("SAVED"));
        assertEquals("SAVED", jdbcTemplate.queryForObject("SELECT status FROM user_coupon_wallets WHERE id=?", String.class, expired));
        mvc.perform(get(URL).header("X-User-Id", BUYER).param("status", "USED"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].wallet.used_order_id").value("8811"));
        mvc.perform(get(URL).header("X-User-Id", BUYER).param("status", "SAVED"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test void keysetHasNoDuplicatesAndCursorCannotCrossOwnerOrFilter() throws Exception {
        long first = wallet(BUYER, "SAVED", "1 day");
        long second = wallet(BUYER, "SAVED", "1 day");
        long third = wallet(BUYER, "SAVED", "1 day");
        var result = mvc.perform(get(URL).header("X-User-Id", BUYER).param("limit", "2"))
                .andExpect(jsonPath("$.items[0].wallet.wallet_id").value(Long.toString(third)))
                .andExpect(jsonPath("$.items[1].wallet.wallet_id").value(Long.toString(second)))
                .andReturn();
        String cursor = objectMapper.readTree(result.getResponse().getContentAsString()).get("next_cursor").asText();
        mvc.perform(get(URL).header("X-User-Id", BUYER).param("cursor", cursor).param("limit", "2"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].wallet.wallet_id").value(Long.toString(first)))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get(URL).header("X-User-Id", OTHER).param("cursor", cursor),
                get(URL).header("X-User-Id", BUYER).param("cursor", cursor).param("status", "SAVED")}) {
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
    }

    @Test void rejectsUnauthenticatedInvalidStatusCursorAndLimits() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        for (var pair : new String[][]{{"status", "UNKNOWN"}, {"cursor", ""}, {"cursor", "invalid"},
                {"limit", "0"}, {"limit", "101"}, {"limit", "1.5"}}) {
            mvc.perform(get(URL).header("X-User-Id", BUYER).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(URL).header("X-User-Id", BUYER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
}
