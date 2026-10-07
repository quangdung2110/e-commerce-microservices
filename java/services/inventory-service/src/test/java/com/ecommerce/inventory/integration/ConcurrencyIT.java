package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** No oversell under hot-SKU contention, and release versus dispatch has exactly one winner. */
class ConcurrencyIT extends AbstractIntegrationTest {

    @Test
    void hotSkuNeverOversells() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "hot", 7);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String orderId = "order-" + i;
                results.add(pool.submit(() -> reserve(orderId, "l1", wh, "hot", "1")));
            }
            int reserved = 0;
            int rejected = 0;
            int busy = 0;
            for (Future<Response> result : results) {
                Response response = result.get();
                if (response.status() == 200 && "RESERVED".equals(response.body().path("outcome").asText())) {
                    reserved++;
                } else if (response.status() == 200) {
                    rejected++;
                } else {
                    assertThat(response.body().path("code").asText()).isEqualTo("INVENTORY_BUSY");
                    busy++;
                }
            }
            assertThat(reserved).isEqualTo(7);
            assertThat(reserved + rejected + busy).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        JsonNode position = position(wh, "hot");
        assertThat(position.path("available_to_sell").asLong()).isZero();
        assertThat(position.path("reserved").asLong()).isEqualTo(7);
    }

    @Test
    void releaseAndDispatchRaceHasExactlyOneWinner() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku", 2);
        reserve("order-1", "l1", wh, "sku", "2");
        workflow(HttpMethod.POST, "/internal/inventory/v1/reservations/order-1/commit",
                Map.of("payment_reference", "p"), key());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Response> release = () -> workflow(HttpMethod.POST,
                    "/internal/inventory/v1/reservations/order-1/release-committed-before-dispatch",
                    Map.of("reason", "ADMIN_FORCE_REFUND_BEFORE_DISPATCH"), key());
            Callable<Response> ship = () -> dispatch("order-1", "l1", wh, "sku", 2);
            Future<Response> a = pool.submit(release);
            Future<Response> b = pool.submit(ship);
            int winners = (a.get().status() == 200 ? 1 : 0) + (b.get().status() == 200 ? 1 : 0);
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        JsonNode position = position(wh, "sku");
        assertThat(position.path("committed").asLong()).isZero();
        // Either the release won (2 sellable, nothing shipped) or the dispatch won (nothing left).
        assertThat(position.path("on_hand").asLong()).isIn(0L, 2L);
        assertThat(position.path("available_to_sell").asLong()).isEqualTo(position.path("on_hand").asLong());
    }
}
