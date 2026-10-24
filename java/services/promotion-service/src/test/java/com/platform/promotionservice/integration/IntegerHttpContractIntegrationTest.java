package com.platform.promotionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MarkdownDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reject lossy numeric coercion before persistence on both typed creates and merge-patch trees. */
@AutoConfigureMockMvc
class IntegerHttpContractIntegrationTest extends AbstractPromotionIntegrationTest {
    @Autowired MockMvc mvc;
    private static final String ADMIN_ID = "00000000-0000-0000-0000-00000000ad01";

    @ParameterizedTest
    @ValueSource(strings = {"/discount/value/amount_minor", "/discount/value_basis_points",
            "/discount/max_discount/amount_minor", "/min_order_amount/amount_minor",
            "/budget/total_claim_quantity", "/budget/total_redemption_quantity", "/budget/total_budget/amount_minor"})
    void voucherCreateAndPatchRejectDecimalsWithoutChangingDefinition(String pointer) throws Exception {
        var campaign = givenActiveWindowCampaign(Duration.ofHours(1));
        String campaignUrl = "/api/v1/admin/promotion-campaigns/" + campaign.getId();
        ObjectNode valid = objectMapper.valueToTree(promotionRequest(DistributionMode.LIMITED_CLAIM, 2L));
        if (pointer.contains("/value/")) valid.set("discount", objectMapper.valueToTree(new DiscountDto.Fixed("FIXED_VND", MoneyDto.vnd(10000))));
        ObjectNode decimal = valid.deepCopy();
        setDecimal(decimal, pointer);
        mvc.perform(post(campaignUrl + "/promotions").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).content(decimal.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(campaignUrl + "/promotions").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"))
                .andExpect(jsonPath("$.items.length()").value(0));
        var created = mvc.perform(post(campaignUrl + "/promotions").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"2\"")).andReturn();
        JsonNode expected = objectMapper.readTree(created.getResponse().getContentAsString());
        String childUrl = "/api/v1/admin/promotions/" + expected.get("id").asText();
        String field = pointer.split("/")[1];
        ObjectNode patchBody = objectMapper.createObjectNode().set(field, decimal.get(field));
        mvc.perform(patch(childUrl).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN").header("If-Match", "\"2\"")
                        .contentType("application/merge-patch+json").content(patchBody.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        var after = mvc.perform(get(childUrl).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\"")).andReturn();
        assertEquals(expected, objectMapper.readTree(after.getResponse().getContentAsString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/discount/value/amount_minor", "/discount/value_basis_points", "/discount/max_discount/amount_minor"})
    void markdownCreateAndPatchRejectDecimals(String pointer) throws Exception {
        var campaign = givenActiveWindowCampaign(Duration.ofHours(1));
        String url = "/api/v1/admin/promotion-campaigns/" + campaign.getId() + "/markdown-rules";
        DiscountDto discount = pointer.contains("/value/") ? new DiscountDto.Fixed("FIXED_VND", MoneyDto.vnd(10000))
                : new DiscountDto.Percentage("PERCENTAGE", 1234, MoneyDto.vnd(20000));
        ObjectNode valid = objectMapper.valueToTree(new MarkdownDtos.CreateMarkdownRuleRequest(UUID.randomUUID(), discount, 1, true));
        ObjectNode decimal = valid.deepCopy(); setDecimal(decimal, pointer);
        mvc.perform(post(url).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(decimal.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        var created = mvc.perform(post(url).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
                .andExpect(status().isCreated()).andReturn();
        JsonNode expected = objectMapper.readTree(created.getResponse().getContentAsString());
        String childUrl = "/api/v1/admin/markdown-rules/" + expected.get("id").asText();
        mvc.perform(patch(childUrl).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN").header("If-Match", "\"2\"")
                        .contentType("application/merge-patch+json").content(objectMapper.createObjectNode().set("discount", decimal.get("discount")).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        var after = mvc.perform(get(childUrl).header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"))
                .andExpect(header().string("ETag", "\"2\"")).andReturn();
        assertEquals(expected, objectMapper.readTree(after.getResponse().getContentAsString()));
    }

    private void setDecimal(ObjectNode body, String pointer) {
        int lastSlash = pointer.lastIndexOf('/');
        ((ObjectNode) body.at(pointer.substring(0, lastSlash))).put(pointer.substring(lastSlash + 1), 1234.9);
    }
}
