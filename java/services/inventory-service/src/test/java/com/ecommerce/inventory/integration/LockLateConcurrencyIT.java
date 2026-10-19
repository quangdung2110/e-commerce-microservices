package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-033: the stock row is locked last, by one guarded update. These tests pin what must not
 * change: no oversell when Reserve, Commit and Release contend for a scarce SKU, no deadlock
 * between multi-line orders in opposite line order, an exact {@code REJECT_ALL} that leaves no
 * trace, durable replay, and movements that always add up to the buckets.
 *
 * <p>Internal retries are off: a deadlock or lock timeout surfaces as {@code 503 INVENTORY_BUSY}
 * instead of being retried away, so the status assertions also prove there was none.
 */
@TestPropertySource(properties = "inventory.command.internal-retries=0")
class LockLateConcurrencyIT extends AbstractIntegrationTest {

    private static final String BASE = "/internal/inventory/v1";

    @Test
    void scarceSkuNeverOversellsUnderReserveCommitAndRelease() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "hot", 20);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<String> reserved = reserveAll(pool, wh, "hot", "first-", 60);
            assertThat(reserved).hasSize(20);
            assertThat(position(wh, "hot").path("available_to_sell").asLong()).isZero();

            // Commit half, release the other half, while a second wave competes for released stock.
            List<Future<Response>> finishes = new ArrayList<>();
            for (int i = 0; i < reserved.size(); i++) {
                String orderId = reserved.get(i);
                boolean commit = i % 2 == 0;
                finishes.add(pool.submit(() -> commit
                        ? workflow(HttpMethod.POST, BASE + "/reservations/" + orderId + "/commit",
                                Map.of("payment_reference", "pay-" + orderId), key())
                        : workflow(HttpMethod.POST, BASE + "/reservations/" + orderId + "/release",
                                Map.of("reason", "PAYMENT_FAILED"), key())));
            }
            List<Future<Response>> secondWave = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String orderId = "second-" + i;
                secondWave.add(pool.submit(() -> reserve(orderId, "l1", wh, "hot", "1")));
            }
            for (Future<Response> finish : finishes) {
                assertThat(finish.get().status()).isEqualTo(200);
            }
            int secondReserved = 0;
            for (Future<Response> result : secondWave) {
                Response response = result.get();
                assertThat(response.status()).isEqualTo(200);
                if ("RESERVED".equals(response.body().path("outcome").asText())) {
                    secondReserved++;
                } else {
                    assertThat(response.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
                }
            }
            // Ten units were released; the second wave can hold at most those ten.
            assertThat(secondReserved).isBetween(0, 10);

            JsonNode position = position(wh, "hot");
            assertThat(position.path("committed").asLong()).isEqualTo(10);
            assertThat(position.path("reserved").asLong()).isEqualTo(secondReserved);
            assertThat(position.path("available_to_sell").asLong()).isEqualTo(10 - secondReserved);
            assertThat(position.path("on_hand").asLong()).isEqualTo(20);
            assertMovementsMatchBuckets(wh, "hot");
            assertThat(countLines("hot", "RESERVED")).isEqualTo(secondReserved);
            assertThat(countLines("hot", "COMMITTED")).isEqualTo(10);
            assertThat(countLines("hot", "RELEASED")).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void multiLineOrdersInOppositeLineOrderDoNotDeadlock() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 1000);
        receive(wh, "sku-b", 1000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                String orderId = "order-" + i;
                boolean aFirst = i % 2 == 0;
                results.add(pool.submit(() -> aFirst
                        ? reserve(orderId, "l1", wh, "sku-a", "1", "l2", wh, "sku-b", "1")
                        : reserve(orderId, "l1", wh, "sku-b", "1", "l2", wh, "sku-a", "1")));
            }
            List<String> reserved = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                Response response = results.get(i).get();
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body().path("outcome").asText()).isEqualTo("RESERVED");
                reserved.add("order-" + i);
            }
            List<Future<Response>> commits = new ArrayList<>();
            for (String orderId : reserved) {
                commits.add(pool.submit(() -> workflow(HttpMethod.POST, BASE + "/reservations/" + orderId + "/commit",
                        Map.of("payment_reference", "pay-" + orderId), key())));
            }
            for (Future<Response> commit : commits) {
                assertThat(commit.get().status()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        for (String sku : List.of("sku-a", "sku-b")) {
            JsonNode position = position(wh, sku);
            assertThat(position.path("committed").asLong()).isEqualTo(60);
            assertThat(position.path("reserved").asLong()).isZero();
            assertThat(position.path("available_to_sell").asLong()).isEqualTo(940);
            assertMovementsMatchBuckets(wh, sku);
        }
    }

    @Test
    void rejectAllNamesEveryUnavailableLineAndLeavesNoTrace() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        receive(wh, "sku-b", 1);
        receive(wh, "sku-c", 10);

        // Two short lines: both are named, the sufficient line changes nothing.
        Response shortLines = reserve("order-1", "l1", wh, "sku-a", "2", "l2", wh, "sku-b", "3",
                "l3", wh, "sku-c", "20");
        assertThat(shortLines.status()).isEqualTo(200);
        assertThat(shortLines.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(texts(shortLines.body().path("rejected_lines"))).containsExactly("l2", "l3");

        // An absent position and a short line: both are named, as before ADR-033.
        Response absentAndShort = reserve("order-2", "l1", wh, "sku-unknown", "1", "l2", wh, "sku-b", "2",
                "l3", wh, "sku-a", "1");
        assertThat(absentAndShort.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(texts(absentAndShort.body().path("rejected_lines"))).containsExactly("l1", "l2");

        for (String orderId : List.of("order-1", "order-2")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_reservation WHERE order_id = ?",
                    Integer.class, orderId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_line WHERE order_id = ?",
                    Integer.class, orderId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE source_operation_id = ?",
                    Integer.class, orderId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry WHERE target_id = ?",
                    Integer.class, orderId)).isZero();
        }
        assertThat(position(wh, "sku-a").path("available_to_sell").asLong()).isEqualTo(5);
        assertThat(position(wh, "sku-b").path("available_to_sell").asLong()).isEqualTo(1);
        assertThat(position(wh, "sku-c").path("available_to_sell").asLong()).isEqualTo(10);
        for (String sku : List.of("sku-a", "sku-b", "sku-c")) {
            assertThat(position(wh, sku).path("reserved").asLong()).isZero();
            assertMovementsMatchBuckets(wh, sku);
        }
    }

    @Test
    void acceptedAndRejectedReservesReplayTheStoredResult() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 1);
        String acceptedKey = key();
        Map<String, Object> accepted = Map.of("order_id", "order-1", "allocation_plan_id", "plan-1",
                "lines", List.of(Map.of("order_line_id", "l1", "warehouse_id", wh, "sku_id", "sku-a", "quantity", 1)));
        Response first = workflow(HttpMethod.POST, BASE + "/reservations", accepted, acceptedKey);
        assertThat(first.body().path("outcome").asText()).isEqualTo("RESERVED");

        String rejectedKey = key();
        Map<String, Object> rejected = Map.of("order_id", "order-2", "allocation_plan_id", "plan-2",
                "lines", List.of(Map.of("order_line_id", "l1", "warehouse_id", wh, "sku_id", "sku-a", "quantity", 1)));
        Response second = workflow(HttpMethod.POST, BASE + "/reservations", rejected, rejectedKey);
        assertThat(second.body().path("outcome").asText()).isEqualTo("REJECT_ALL");

        // Freeing the stock must not change the stored REJECT_ALL; replays return what was recorded.
        workflow(HttpMethod.POST, BASE + "/reservations/order-1/release", Map.of("reason", "BUYER_CANCELLED"), key());
        Response firstReplay = workflow(HttpMethod.POST, BASE + "/reservations", accepted, acceptedKey);
        assertThat(firstReplay.body().path("outcome").asText()).isEqualTo("RESERVED");
        assertThat(firstReplay.body().path("replayed").asBoolean()).isTrue();
        Response secondReplay = workflow(HttpMethod.POST, BASE + "/reservations", rejected, rejectedKey);
        assertThat(secondReplay.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(secondReplay.body().path("recorded_at")).isEqualTo(second.body().path("recorded_at"));
        assertThat(position(wh, "sku-a").path("available_to_sell").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE source_operation_id = 'order-1'",
                Integer.class)).isEqualTo(2);
        assertMovementsMatchBuckets(wh, "sku-a");
    }

    private List<String> reserveAll(ExecutorService pool, String wh, String sku, String prefix, int count)
            throws Exception {
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String orderId = prefix + i;
            calls.add(() -> reserve(orderId, "l1", wh, sku, "1"));
        }
        List<Future<Response>> results = pool.invokeAll(calls);
        List<String> reserved = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Response response = results.get(i).get();
            assertThat(response.status()).isEqualTo(200);
            if ("RESERVED".equals(response.body().path("outcome").asText())) {
                reserved.add(prefix + i);
            } else {
                assertThat(response.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
            }
        }
        return reserved;
    }

    /** Every bucket equals the sum of its movements: nothing was changed without evidence. */
    private void assertMovementsMatchBuckets(String wh, String sku) throws Exception {
        Map<String, Object> sums = jdbc.queryForMap("""
                SELECT coalesce(sum(delta_on_hand), 0) AS on_hand, coalesce(sum(delta_available), 0) AS available,
                       coalesce(sum(delta_reserved), 0) AS reserved, coalesce(sum(delta_committed), 0) AS committed,
                       coalesce(sum(delta_quarantine), 0) AS quarantine
                FROM stock_movement WHERE warehouse_id = ? AND sku_id = ?""", wh, sku);
        JsonNode position = position(wh, sku);
        assertThat(((Number) sums.get("on_hand")).longValue()).isEqualTo(position.path("on_hand").asLong());
        assertThat(((Number) sums.get("available")).longValue()).isEqualTo(position.path("available_to_sell").asLong());
        assertThat(((Number) sums.get("reserved")).longValue()).isEqualTo(position.path("reserved").asLong());
        assertThat(((Number) sums.get("committed")).longValue()).isEqualTo(position.path("committed").asLong());
        assertThat(((Number) sums.get("quarantine")).longValue()).isEqualTo(position.path("quarantine").asLong());
    }

    private int countLines(String sku, String state) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_line WHERE sku_id = ? AND state = ?",
                Integer.class, sku, state);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
