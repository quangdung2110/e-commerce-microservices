package com.platform.promotionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP wiring theo contracts/http/promotion/v1/openapi.yaml: identity, ETag/If-Match, problem+json, snake_case. */
@AutoConfigureMockMvc
class HttpContractIntegrationTest extends AbstractPromotionIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static final String ADMIN_ID = "00000000-0000-0000-0000-00000000ad01";

    @Test
    @DisplayName("admin campaign lifecycle over HTTP: 403 without ADMIN, 201 + ETag, 412 on stale If-Match, merge-patch")
    void adminCampaignHttp() throws Exception {
        Instant start = Instant.now().minus(Duration.ofHours(1));
        Instant end = Instant.now().plus(Duration.ofHours(1));
        String body = """
                {"code":"HTTP-CAMP-1","name":"HTTP","description":null,"start_time":"%s","end_time":"%s","future_field":1}
                """.formatted(start, end);

        mvc.perform(post("/api/v1/admin/promotion-campaigns")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MvcResult created = mvc.perform(post("/api/v1/admin/promotion-campaigns")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.publication_status").value("DRAFT"))
                .andExpect(jsonPath("$.phase").value("DRAFT"))
                .andExpect(jsonPath("$.created_by").value(ADMIN_ID))
                .andReturn();
        JsonNode campaign = objectMapper.readTree(created.getResponse().getContentAsString());
        String campaignId = campaign.get("id").asText();

        mvc.perform(patch("/api/v1/admin/promotion-campaigns/" + campaignId)
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .header("If-Match", "\"9\"")
                        .contentType("application/merge-patch+json").content("{\"name\":\"renamed\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_REVISION_MISMATCH"));

        mvc.perform(patch("/api/v1/admin/promotion-campaigns/" + campaignId)
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .header("If-Match", "\"1\"")
                        .contentType("application/merge-patch+json").content("{\"name\":\"renamed\",\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.revision").value(2));

        mvc.perform(get("/api/v1/admin/promotion-campaigns").param("phase", "DRAFT")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(campaignId))
                .andExpect(jsonPath("$.next_cursor").value((Object) null));

        mvc.perform(post("/api/v1/admin/promotion-campaigns/" + campaignId + "/transitions")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_status\":\"CANCELLED\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("customer save/claim and internal routes enforce identity and return contract shapes")
    void customerAndInternalHttp() throws Exception {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        String promotionId = Long.toString(promotion.getId());
        String user = UUID.randomUUID().toString();
        String context = """
                {"context":{"subtotal":{"amount_minor":350000,"currency":"VND"},"payment_method":"STRIPE",
                 "checkout_channel":"web","items":[{"product_id":"%s","sku_id":"S1","quantity":1,
                 "unit_price":{"amount_minor":350000,"currency":"VND"},"category_ids":["c1"]}]}}
                """.formatted(UUID.randomUUID());

        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotionId)
                        .contentType(MediaType.APPLICATION_JSON).content(context))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotionId)
                        .header("X-User-Id", user).header("X-User-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(context))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.wallet.status").value("SAVED"))
                .andExpect(jsonPath("$.wallet.user_id").value(user))
                .andExpect(jsonPath("$.evaluation.eligible").value(true))
                .andExpect(jsonPath("$.evaluation.discount.amount_minor").value(35000))
                .andExpect(jsonPath("$.evaluation.discount.currency").value("VND"))
                .andExpect(jsonPath("$.evaluation.source").value("WALLET"));

        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotionId)
                        .header("X-User-Id", user).header("X-User-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(context))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/me/promotion-voucher-claims")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promotion_id\":\"" + promotionId + "\",\"expected_campaign_revision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISTRIBUTION_MODE_MISMATCH"));

        String calculation = """
                {"user_id":"%s","user_tier":"SILVER","promotion_id":"%s",%s
                """.formatted(user, promotionId, context.trim().substring(1));
        mvc.perform(post("/internal/v1/promotion-discount-calculations")
                        .header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(calculation))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/promotion-discount-calculations")
                        .header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON).content(calculation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reason").value("ELIGIBLE"))
                .andExpect(jsonPath("$.final_amount.amount_minor").value(315000))
                .andExpect(jsonPath("$.budget_guard").value("AVAILABLE"));

        String reserve = """
                {"order_id":"777","user_id":"%s","user_tier":"SILVER","promotion_id":"%s",
                 "expected_discount":{"amount_minor":35000,"currency":"VND"},%s
                """.formatted(user, promotionId, context.trim().substring(1));
        mvc.perform(post("/internal/v1/promotion-allocation/reservations")
                        .header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(reserve))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RESERVED"))
                .andExpect(jsonPath("$.discount.amount_minor").value(35000));

        String confirm = "{\"order_id\":\"777\",\"promotion_id\":\"" + promotionId
                + "\",\"expected_discount\":{\"amount_minor\":35000,\"currency\":\"VND\"}}";
        mvc.perform(post("/internal/v1/promotion-allocation/confirmations")
                        .header("X-Service-Identity", "order-service")
                        .header("Idempotency-Key", "REDEEM_FINAL:778:" + promotionId)
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isBadRequest());
        MvcResult confirmed = mvc.perform(post("/internal/v1/promotion-allocation/confirmations")
                        .header("X-Service-Identity", "order-service")
                        .header("Idempotency-Key", "REDEEM_FINAL:777:" + promotionId)
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CONFIRM"))
                .andExpect(jsonPath("$.wallet_status").value("USED"))
                .andReturn();
        assertThat(confirmed.getResponse().getContentAsString()).contains("\"completed_at\"");

        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "order-service")
                        .header("Idempotency-Key", "CANCEL_FINAL:777:" + promotionId)
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CANCEL_CONFIRMED"))
                .andExpect(jsonPath("$.wallet_status").value("SAVED"));

        mvc.perform(post("/internal/v1/promotion-allocation/cancellations")
                        .header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"777\",\"promotion_id\":\"" + promotionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_RESERVATION"));
    }
}
