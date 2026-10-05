package com.ecommerce.order.integration;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.ContractSchemas;
import com.ecommerce.order.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WF-ORDER-001/002: detail visibility and PostgreSQL search cards. */
class QueryIT extends AbstractIntegrationTest {

    @Test
    void detailIsOwnerOnlyAndPrivate() throws Exception {
        String user = UUID.randomUUID().toString();
        long orderId = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(),
                200_000, "promo-q"));
        Response mine = buyerGet("/api/v1/orders/" + orderId, user);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.raw().getResponse().getHeader("Cache-Control")).contains("no-store").contains("private");
        JsonNode body = mine.body();
        assertThat(body.path("order_id").asText()).isEqualTo(Long.toString(orderId));
        assertThat(body.path("buyer_tab").asText()).isEqualTo("TO_PAY");
        assertThat(body.path("items")).hasSize(2);
        assertThat(body.path("promotions").get(0).path("discount_amount").path("amount_minor").asLong()).isEqualTo(200_000);
        assertThat(body.path("payment").path("active_attempt_number").asInt()).isEqualTo(1);
        assertThat(body.path("refund_case").isNull()).isTrue();
        assertThat(body.path("available_actions").path("can_cancel").asBoolean()).isTrue();
        assertThat(body.path("items").get(0).path("delivery_address").path("ward_code").asText()).isEqualTo("26734");
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderDetail", body);
        // The validator is not vacuous: a mutated body is rejected.
        assertThat(ContractSchemas.errors(ContractSchemas.PUBLIC, "OrderDetail",
                ((com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy()).put("buyer_tab", "BOGUS"))).isNotEmpty();
        assertThat(ContractSchemas.errors(ContractSchemas.PUBLIC, "OrderDetail",
                ((com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy()).put("unexpected", 1))).isNotEmpty();
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "Problem",
                buyerGet("/api/v1/orders/" + orderId, UUID.randomUUID().toString()).body());

        assertThat(buyerGet("/api/v1/orders/" + orderId, UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(buyerGet("/api/v1/orders/0" + orderId, user).status()).isEqualTo(404);
        assertThat(call(admin(MockMvcRequestBuilders.get("/api/v1/admin/orders/" + orderId))).status()).isEqualTo(200);
        assertThat(buyerGet("/api/v1/admin/orders/" + orderId, user).status()).isEqualTo(403);
        assertThat(call(MockMvcRequestBuilders.get("/api/v1/orders/" + orderId)).status()).isEqualTo(401);
    }

    @Test
    void searchReturnsOneCardPerOrderWithKeysetPaging() throws Exception {
        String user = UUID.randomUUID().toString();
        long first = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        long third = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));

        Response page1 = buyerGet("/api/v1/orders?tab=TO_PAY&limit=2", user);
        assertThat(page1.status()).isEqualTo(200);
        assertThat(page1.body().path("total_orders").asLong()).isEqualTo(3);
        assertThat(page1.body().path("items")).hasSize(2);
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderSearchPage", page1.body());
        JsonNode card = page1.body().path("items").get(0);
        assertThat(card.path("order_id").asText()).isEqualTo(Long.toString(third));
        assertThat(card.path("item_count").asInt()).isEqualTo(3);
        assertThat(card.path("remaining_item_count").asInt()).isEqualTo(2);
        assertThat(card.path("primary_item_name").asText()).isEqualTo("Áo thun cổ tròn");
        assertThat(card.path("primary_store_name").asText()).isEqualTo("PLATFORM");
        assertThat(card.path("ui_primary_status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(card.path("ui_status_rank").asInt()).isEqualTo(10);
        String cursor = page1.body().path("next_cursor").asText();
        Response page2 = buyerGet("/api/v1/orders?tab=TO_PAY&limit=2&cursor=" + cursor, user);
        assertThat(page2.body().path("items")).hasSize(1);
        assertThat(page2.body().path("items").get(0).path("order_id").asText()).isEqualTo(Long.toString(first));
        assertThat(page2.body().path("next_cursor").isNull()).isTrue();
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderSearchPage", page2.body());

        // Accent-insensitive substring on the immutable snapshot name, including đ.
        Response accentless = call(buyer(MockMvcRequestBuilders.get("/api/v1/orders").param("q", "BINH NUOC"), user));
        assertThat(accentless.body().path("total_orders").asLong()).isEqualTo(3);
        Response withD = call(buyer(MockMvcRequestBuilders.get("/api/v1/orders").param("q", "đỏ"), user));
        assertThat(withD.body().path("total_orders").asLong()).isZero();
        assertThat(buyerGet("/api/v1/orders?q=khong-ton-tai", user).body().path("total_orders").asLong()).isZero();
        assertThat(buyerGet("/api/v1/orders?tab=COMPLETED", user).body().path("total_orders").asLong()).isZero();
        assertThat(buyerGet("/api/v1/orders?cursor=not-a-cursor", user).status()).isEqualTo(400);

        Response admin = call(admin(MockMvcRequestBuilders.get("/api/v1/admin/orders?buyer_id=" + user)));
        assertThat(admin.body().path("total_orders").asLong()).isEqualTo(3);
        assertThat(buyerGet("/api/v1/orders", UUID.randomUUID().toString()).body().path("total_orders").asLong()).isZero();
    }
}
