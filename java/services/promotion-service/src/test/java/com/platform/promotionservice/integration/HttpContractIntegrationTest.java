package com.platform.promotionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

        // Internal callers send a TrustedCheckoutContext: the channel is always CHECKOUT.
        String trusted = context.replace("\"web\"", "\"CHECKOUT\"");
        String calculation = """
                {"user_id":"%s","user_tier":"SILVER","promotion_id":"%s",%s
                """.formatted(user, promotionId, trusted.trim().substring(1));
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
                """.formatted(user, promotionId, trusted.trim().substring(1));
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

        // Legacy technical cancellation (PROMO-BR-018): only promotion-legacy-migrator, with an immutable proof.
        String record = "7c1d8c5e-3d2f-4b49-9a3e-52b0d9d1f001";
        String digest = "A".repeat(64);
        String legacy = legacyCancel("777", promotionId, 35000, record, digest);
        String cancelKey = "CANCEL_FINAL:777:" + promotionId;
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "order-service")
                        .header("Idempotency-Key", cancelKey).header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Even the migrator is denied without the proof, or with one that is not the body's migration record.
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (String wrongProof : new String[] {"7c1d8c5e-3d2f-4b49-9a3e-52b0d9d1f002", "not-a-uuid"}) {
            mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                            .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                            .header("X-Legacy-Migration-Proof", wrongProof)
                            .contentType(MediaType.APPLICATION_JSON).content(legacy))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        // The body is strict (additionalProperties: false) and the digest must be 64 hex characters.
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyCancel("777", promotionId, 35000, record, "abc")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacy.substring(0, legacy.lastIndexOf('}')) + ",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        // Nothing above touched the confirmed allocation.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon_wallets WHERE promotion_id = ?", String.class, promotion.getId()))
                .isEqualTo("USED");

        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CANCEL_CONFIRMED"))
                .andExpect(jsonPath("$.wallet_status").value("SAVED"));
        // The receipt keeps the proof (lower-cased digest); replaying with another digest is a payload mismatch.
        assertThat(jdbcTemplate.queryForMap("""
                SELECT migration_record_id::text AS migration_record, pre_watermark_evidence_sha256 AS evidence_digest
                FROM promotion_command_receipts WHERE operation_type = 'CANCEL_CONFIRMED'
                """)).containsEntry("migration_record", record).containsEntry("evidence_digest", digest.toLowerCase());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM promotion_command_receipts
                WHERE operation_type = 'CONFIRM' AND migration_record_id IS NULL
                """, Long.class)).isEqualTo(1L);
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyCancel("777", promotionId, 35000, record, "B".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
        mvc.perform(post("/internal/v1/promotion-allocation/technical-cancellations")
                        .header("X-Service-Identity", "promotion-legacy-migrator").header("Idempotency-Key", cancelKey)
                        .header("X-Legacy-Migration-Proof", record)
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet_status").value("SAVED"));

        mvc.perform(post("/internal/v1/promotion-allocation/cancellations")
                        .header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"777\",\"promotion_id\":\"" + promotionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_RESERVATION"));
    }

    private static String legacyCancel(String orderId, String promotionId, long discount, String record, String digest) {
        return """
                {"order_id":"%s","promotion_id":"%s","expected_discount":{"amount_minor":%d,"currency":"VND"},
                 "migration_record_id":"%s","pre_financial_watermark_evidence_sha256":"%s"}
                """.formatted(orderId, promotionId, discount, record, digest);
    }

    private static String checkoutContext(long subtotal, String channel) {
        return """
                {"subtotal":{"amount_minor":%d,"currency":"VND"},"payment_method":"STRIPE",
                 "checkout_channel":"%s","items":[{"product_id":"%s","sku_id":"S1","quantity":1,
                 "unit_price":{"amount_minor":%d,"currency":"VND"},"category_ids":["c1"]}]}
                """.formatted(subtotal, channel, UUID.randomUUID(), subtotal);
    }

    @Test
    @DisplayName("Checkout and Order calls may omit user_tier: Checkout v1 has no tier authority")
    void userTierIsOptional() throws Exception {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        String promotionId = Long.toString(promotion.getId());
        String user = UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotionId)
                        .header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        String calculation = "{\"user_id\":\"" + user + "\",\"promotion_id\":\"" + promotionId + "\",\"context\":"
                + checkoutContext(350_000, "CHECKOUT") + "}";
        mvc.perform(post("/internal/v1/promotion-discount-calculations")
                        .header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON).content(calculation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.discount.amount_minor").value(35000));

        String reserve = "{\"order_id\":\"901\",\"user_id\":\"" + user + "\",\"promotion_id\":\"" + promotionId
                + "\",\"expected_discount\":{\"amount_minor\":35000,\"currency\":\"VND\"},\"context\":"
                + checkoutContext(350_000, "CHECKOUT") + "}";
        mvc.perform(post("/internal/v1/promotion-allocation/reservations")
                        .header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(reserve))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RESERVED"));
    }

    @Test
    @DisplayName("PUT promotion-vouchers without a context only saves: evaluation is null (Promotion ADR-018)")
    void saveWithoutContext() throws Exception {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        String user = UUID.randomUUID().toString();

        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotion.getId())
                        .header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wallet.status").value("SAVED"))
                .andExpect(jsonPath("$.evaluation").value((Object) null));
        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotion.getId())
                        .header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation").value((Object) null));
    }

    @Test
    @DisplayName("voucher options: only checkout-service, wallet and public vouchers ranked with display data")
    void voucherOptions() throws Exception {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity saved = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        PromotionEntity open = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        String user = UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/me/promotion-vouchers/" + saved.getId())
                        .header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        String url = "/internal/v1/promotion-voucher-options";
        String body = "{\"user_id\":\"" + user + "\",\"context\":" + checkoutContext(350_000, "CHECKOUT") + "}";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // Per-user data (wallet): never cached (OpenAPI Cache-Control no-store).
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.evaluated_at").exists())
                // Equal discount: promotion_id ascending, so the saved one comes first.
                .andExpect(jsonPath("$.items[0].evaluation.promotion_id").value(Long.toString(saved.getId())))
                .andExpect(jsonPath("$.items[0].evaluation.source").value("WALLET"))
                .andExpect(jsonPath("$.items[0].evaluation.distribution_mode").value("PUBLIC_SAVE"))
                .andExpect(jsonPath("$.items[0].evaluation.eligible").value(true))
                .andExpect(jsonPath("$.items[0].evaluation.discount.amount_minor").value(35000))
                .andExpect(jsonPath("$.items[0].display.code").value(saved.getCode()))
                .andExpect(jsonPath("$.items[0].display.name").value("Ten percent"))
                .andExpect(jsonPath("$.items[0].display.description").value((Object) null))
                .andExpect(jsonPath("$.items[0].display.discount.type").value("PERCENTAGE"))
                .andExpect(jsonPath("$.items[0].display.discount.value_basis_points").value(1000))
                .andExpect(jsonPath("$.items[0].display.discount.max_discount.amount_minor").value(50000))
                .andExpect(jsonPath("$.items[0].display.min_order_amount.amount_minor").value(100000))
                .andExpect(jsonPath("$.items[0].display.valid_until").exists())
                .andExpect(jsonPath("$.items[1].evaluation.promotion_id").value(Long.toString(open.getId())))
                .andExpect(jsonPath("$.items[1].evaluation.source").value("PUBLIC"))
                .andExpect(jsonPath("$.items[1].evaluation.wallet_id").value((Object) null));

        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + user + "\",\"include_public\":false,\"context\":"
                                + checkoutContext(350_000, "CHECKOUT") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].evaluation.source").value("WALLET"));

        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + user + "\",\"limit\":1,\"context\":"
                                + checkoutContext(350_000, "CHECKOUT") + "}"))
                .andExpect(jsonPath("$.items.length()").value(1));

        // Below the minimum order: ineligible vouchers are listed with their reason unless excluded.
        String small = "\"context\":" + checkoutContext(50_000, "CHECKOUT");
        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + user + "\"," + small + "}"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].evaluation.eligible").value(false))
                .andExpect(jsonPath("$.items[0].evaluation.reason").value("MIN_ORDER_NOT_MET"));
        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + user + "\",\"include_ineligible\":false," + small + "}"))
                .andExpect(jsonPath("$.items.length()").value(0));

        // The context of this operation is trusted: only the CHECKOUT channel.
        mvc.perform(post(url).header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + user + "\",\"context\":" + checkoutContext(350_000, "web") + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("calculate and reserve take a TrustedCheckoutContext: any channel but CHECKOUT is a 400")
    void internalContextsAcceptOnlyTheCheckoutChannel() throws Exception {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        publish(campaign);
        String promotionId = Long.toString(promotion.getId());
        String user = UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/me/promotion-vouchers/" + promotionId).header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        String calculation = "{\"user_id\":\"" + user + "\",\"promotion_id\":\"" + promotionId + "\",\"context\":"
                + checkoutContext(350_000, "web") + "}";
        mvc.perform(post("/internal/v1/promotion-discount-calculations").header("X-Service-Identity", "checkout-service")
                        .contentType(MediaType.APPLICATION_JSON).content(calculation))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("context.checkout_channel"));

        String reserve = "{\"order_id\":\"951\",\"user_id\":\"" + user + "\",\"promotion_id\":\"" + promotionId
                + "\",\"expected_discount\":{\"amount_minor\":35000,\"currency\":\"VND\"},\"context\":"
                + checkoutContext(350_000, "web") + "}";
        mvc.perform(post("/internal/v1/promotion-allocation/reservations").header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(reserve))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("context.checkout_channel"));
        // Nothing was reserved by the rejected call: the same order reserves normally with the right channel.
        mvc.perform(post("/internal/v1/promotion-allocation/reservations").header("X-Service-Identity", "order-service")
                        .contentType(MediaType.APPLICATION_JSON).content(reserve.replace("\"web\"", "\"CHECKOUT\"")))
                .andExpect(status().isCreated());
        // The customer queries keep their own free-form channel: it is the client's context, not a trusted one.
        mvc.perform(post("/api/v1/promotion-discovery").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":" + checkoutContext(350_000, "web") + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("requests no operation matches are client errors (404, 405, 400) in problem+json, not INTERNAL_ERROR")
    void unroutedRequestsAreClientErrors() throws Exception {
        String admin = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/me/nothing-here").header("X-User-Id", admin))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
        mvc.perform(delete("/api/v1/admin/promotion-campaigns").header("X-User-Id", admin).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // PATCH is merge-patch only; another Content-Type, or a body that is not JSON, is a bad request (no 415).
        mvc.perform(patch("/api/v1/admin/promotion-campaigns/1").header("X-User-Id", admin)
                        .header("X-User-Roles", "ADMIN").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/promotion-discovery").header("X-User-Id", admin)
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // Identity is checked before routing: an unknown internal route is still 403 for a caller outside every group.
        mvc.perform(get("/internal/v1/nothing").header("X-Service-Identity", "order-service"))
                .andExpect(status().isForbidden());
    }
}
