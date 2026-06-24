package com.platform.inventoryservice.redis;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test cho 3 Lua scripts (reserve / release / deduct) defined in
 * src/main/resources/lua/. Mục tiêu: assert đầy đủ return codes từ plan
 * M03 §12 — không verify cluster hash-tag slot (chỉ assert substring pattern).
 * <p>
 * Dùng plain JUnit + TestContainers (standalone Redis) để cô lập khỏi Spring
 * context và main RedisConfig (cluster-mode). M06 sẽ thêm test với
 * RedisClusterContainer cho cluster hash-tag thật.
 */
@Testcontainers
class LuaScriptSmokeTest {

    @Container
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));

    static StringRedisTemplate redis;
    static DefaultRedisScript<List> reserveScript;
    static DefaultRedisScript<List> releaseScript;
    static DefaultRedisScript<List> deductScript;

    static final long IDEMP_TTL_SEC = 30L * 24 * 60 * 60; // 30 days (plan §3.2)
    static final String SKU = "sku-001";
    static final long WH = 5L;
    static final String HASH_TAG = "{" + SKU + ":" + WH + "}";

    @BeforeAll
    static void setupAll() {
        LettuceConnectionFactory cf = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();

        reserveScript = loadScript("lua/reserve_inventory.lua");
        releaseScript = loadScript("lua/release_inventory.lua");
        deductScript = loadScript("lua/deduct_inventory.lua");
    }

    @AfterAll
    static void tearDownAll() {
        if (redis != null && redis.getConnectionFactory() != null) {
            ((LettuceConnectionFactory) redis.getConnectionFactory()).destroy();
        }
    }

    @BeforeEach
    void flushDb() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static DefaultRedisScript<List> loadScript(String path) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(List.class);
        return s;
    }

    // ============================================================
    // 1. Hash-tag invariant (plan §9)
    // ============================================================

    @Test
    void all_keys_share_hash_tag() {
        assertThat(stockKey()).contains(HASH_TAG);
        assertThat(idempKey("reserve", 1L)).contains(HASH_TAG);
        assertThat(idempKey("release", 1L)).contains(HASH_TAG);
        assertThat(idempKey("deduct", 1L)).contains(HASH_TAG);
        assertThat(streamKey()).contains(HASH_TAG);
        assertThat(suspensionKey()).contains(HASH_TAG);
    }

    // ============================================================
    // 2. Script load + parse (plan §12)
    // ============================================================

    @Test
    void scripts_load_with_sha1() {
        assertThat(reserveScript.getSha1()).isNotBlank();
        assertThat(releaseScript.getSha1()).isNotBlank();
        assertThat(deductScript.getSha1()).isNotBlank();
    }

    // ============================================================
    // 3. Reserve (plan §6)
    // ============================================================

    @Test
    void reserve_ok_returns_1() {
        seedStock(100, 0, 100);

        LuaResult r = invoke(reserveScript, "reserve", 10001L, 3, null);

        assertThat(r.code()).isEqualTo(1L);
        assertThat(r.reason()).isEmpty();
        assertStock(100, 3, 97);
        assertStreamCount(1);
    }

    @Test
    void reserve_out_of_stock_returns_0() {
        seedStock(10, 0, 1);

        LuaResult r = invoke(reserveScript, "reserve", 10002L, 5, null);

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("OUT_OF_STOCK");
        // stock not mutated
        assertStock(10, 0, 1);
        // idempotency key rolled back so caller can retry
        assertThat(redis.hasKey(idempKey("reserve", 10002L))).isFalse();
        // no event emitted
        assertStreamCount(0);
    }

    @Test
    void reserve_idempotent_returns_2() {
        seedStock(100, 0, 100);

        LuaResult first = invoke(reserveScript, "reserve", 10003L, 4, null);
        LuaResult second = invoke(reserveScript, "reserve", 10003L, 4, null);

        assertThat(first.code()).isEqualTo(1L);
        assertThat(second.code()).isEqualTo(2L);
        // stock mutated only once
        assertStock(100, 4, 96);
        // only one stream entry (idempotent hit must NOT emit new event)
        assertStreamCount(1);
    }

    @Test
    void reserve_suspended_returns_0() {
        seedStock(100, 0, 100);
        redis.opsForHash().put(suspensionKey(), "status", "SUSPENDED");

        LuaResult r = invoke(reserveScript, "reserve", 10004L, 1, null);

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("SKU_SUSPENDED");
        assertStock(100, 0, 100); // not mutated
        assertStreamCount(0);
    }

    // ============================================================
    // 4. Release (plan §7)
    // ============================================================

    @Test
    void release_ok_returns_1() {
        seedStock(100, 5, 95);

        LuaResult r = invoke(releaseScript, "release", 20001L, 3, "ORDER_CANCELLED");

        assertThat(r.code()).isEqualTo(1L);
        assertThat(r.reason()).isEmpty();
        assertStock(100, 2, 98);
    }

    @Test
    void release_idempotent_returns_2() {
        seedStock(100, 5, 95);

        LuaResult first = invoke(releaseScript, "release", 20002L, 3, "ORDER_CANCELLED");
        LuaResult second = invoke(releaseScript, "release", 20002L, 3, "ORDER_CANCELLED");

        assertThat(first.code()).isEqualTo(1L);
        assertThat(second.code()).isEqualTo(2L);
        assertStock(100, 2, 98);
        assertStreamCount(1);
    }

    @Test
    void release_underflow_returns_0() {
        seedStock(100, 1, 99);

        LuaResult r = invoke(releaseScript, "release", 20003L, 5, "ORDER_CANCELLED");

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("RELEASE_UNDERFLOW");
        assertStock(100, 1, 99);
        assertStreamCount(0);
    }

    @Test
    void release_suspended_returns_0() {
        seedStock(100, 5, 95);
        redis.opsForHash().put(suspensionKey(), "status", "SUSPENDED");

        LuaResult r = invoke(releaseScript, "release", 20004L, 1, "ORDER_CANCELLED");

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("SKU_SUSPENDED");
        assertStock(100, 5, 95);
    }

    @Test
    void release_change_type_propagates_to_stream() {
        seedStock(100, 5, 95);

        LuaResult r = invoke(releaseScript, "release", 20005L, 2, "RESERVATION_EXPIRED");

        assertThat(r.code()).isEqualTo(1L);
        List<MapRecord<String, Object, Object>> entries =
                redis.opsForStream().range(streamKey(), Range.closed("-", "+"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getValue().get("change_type"))
                .isEqualTo("RESERVATION_EXPIRED");
    }

    // ============================================================
    // 5. Deduct (plan §8)
    // ============================================================

    @Test
    void deduct_ok_returns_1() {
        seedStock(100, 10, 90);

        LuaResult r = invoke(deductScript, "deduct", 30001L, 3, null);

        assertThat(r.code()).isEqualTo(1L);
        assertThat(r.reason()).isEmpty();
        // total -= qty, reserved -= qty, available recomputed = total - reserved
        assertStock(97, 7, 90);
    }

    @Test
    void deduct_invariant_drift_returns_minus_1() {
        // Manually corrupt invariant: total=100, reserved=10, available=50
        // 50 != 100 - 10 (= 90) => drift
        seedStock(100, 10, 50);

        LuaResult r = invoke(deductScript, "deduct", 30002L, 1, null);

        assertThat(r.code()).isEqualTo(-1L);
        assertThat(r.reason()).isEqualTo("INVARIANT_DRIFT");
        // stock not mutated
        assertStock(100, 10, 50);
        assertStreamCount(0);
    }

    @Test
    void deduct_insufficient_reserved_returns_0() {
        seedStock(100, 1, 99);

        LuaResult r = invoke(deductScript, "deduct", 30003L, 5, null);

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("INSUFFICIENT_RESERVED");
        assertStock(100, 1, 99);
    }

    @Test
    void deduct_suspended_returns_0() {
        seedStock(100, 10, 90);
        redis.opsForHash().put(suspensionKey(), "status", "SUSPENDED");

        LuaResult r = invoke(deductScript, "deduct", 30004L, 1, null);

        assertThat(r.code()).isEqualTo(0L);
        assertThat(r.reason()).isEqualTo("SKU_SUSPENDED");
        assertStock(100, 10, 90);
    }

    @Test
    void deduct_idempotent_returns_2() {
        seedStock(100, 10, 90);

        LuaResult first = invoke(deductScript, "deduct", 30005L, 3, null);
        LuaResult second = invoke(deductScript, "deduct", 30005L, 3, null);

        assertThat(first.code()).isEqualTo(1L);
        assertThat(second.code()).isEqualTo(2L);
        assertStock(97, 7, 90);
        assertStreamCount(1);
    }

    // ============================================================
    // Helpers
    // ============================================================

    record LuaResult(long code, String reason) {
        static LuaResult of(List<Object> raw) {
            return new LuaResult(((Number) raw.get(0)).longValue(), (String) raw.get(1));
        }
    }

    private String stockKey() {
        return "inventory:physic:" + HASH_TAG + ":stock";
    }

    private String idempKey(String op, long orderId) {
        return "inventory:idemp:" + op + ":" + HASH_TAG + ":order:" + orderId;
    }

    private String streamKey() {
        return "inventory:stream:" + HASH_TAG + ":events";
    }

    private String suspensionKey() {
        return "inventory:suspended:" + HASH_TAG;
    }

    private void seedStock(int total, int reserved, int available) {
        Map<String, String> hash = Map.of(
                "total", String.valueOf(total),
                "reserved", String.valueOf(reserved),
                "available", String.valueOf(available),
                "updated_at_ms", String.valueOf(System.currentTimeMillis())
        );
        redis.opsForHash().putAll(stockKey(), hash);
    }

    private void assertStock(int total, int reserved, int available) {
        Map<Object, Object> hash = redis.opsForHash().entries(stockKey());
        assertThat(hash.get("total")).isEqualTo(String.valueOf(total));
        assertThat(hash.get("reserved")).isEqualTo(String.valueOf(reserved));
        assertThat(hash.get("available")).isEqualTo(String.valueOf(available));
    }

    private void assertStreamCount(long expected) {
        Long size = redis.opsForStream().size(streamKey());
        assertThat(size).isEqualTo(expected);
    }

    private LuaResult invoke(DefaultRedisScript<List> script, String op, long orderId,
                             int qty, String changeType) {
        List<String> keys = List.of(
                stockKey(), idempKey(op, orderId), streamKey(), suspensionKey());
        String payload = "{\"order_id\":" + orderId + ",\"sku_id\":\"" + SKU + "\"}";
        long nowMs = System.currentTimeMillis();
        Object[] args = (changeType == null)
                ? new Object[]{
                        String.valueOf(qty),
                        String.valueOf(nowMs),
                        String.valueOf(IDEMP_TTL_SEC),
                        payload}
                : new Object[]{
                        String.valueOf(qty),
                        String.valueOf(nowMs),
                        String.valueOf(IDEMP_TTL_SEC),
                        payload,
                        changeType};
        List<Object> raw = redis.execute(script, keys, args);
        return LuaResult.of(raw);
    }
}
