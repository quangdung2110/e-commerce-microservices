package com.platform.promotionservice.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua semantics (data.md §7) trên Redis thật: claim gate/TIME/SPOP/pending, reserve
 * replay/mismatch/exhausted, Confirm-Prepare fence, finalize move counter, cancel release.
 */
@Testcontainers
class LuaScriptTest {

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));

    static StringRedisTemplate redis;
    static RuntimeStore runtime;
    static ClaimStore claims;
    static ReservationStore reservations;

    static final long PROMO = 101L;

    @BeforeAll
    static void setUpAll() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        LuaScripts scripts = new LuaScripts();
        ObjectMapper mapper = new ObjectMapper();
        runtime = new RuntimeStore(redis);
        claims = new ClaimStore(redis, scripts, mapper);
        reservations = new ReservationStore(redis, scripts, mapper);
    }

    @AfterAll
    static void tearDownAll() {
        ((LettuceConnectionFactory) redis.getConnectionFactory()).destroy();
    }

    @BeforeEach
    void flush() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ claim

    @Test
    @DisplayName("claim gate: missing, disabled, stale revision and closed window reject before SPOP")
    void claimGateGuards() {
        UUID user = UUID.randomUUID();
        claims.addToPool(PROMO, java.util.List.of(1L, 2L));
        assertThat(claims.acquire(PROMO, user, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.GATE_MISSING);

        runtime.writeClaimGate(PROMO, false, 3, now() - 1000, now() + 60_000);
        assertThat(claims.acquire(PROMO, user, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.GATE_DISABLED);

        runtime.writeClaimGate(PROMO, true, 4, now() - 1000, now() + 60_000);
        assertThat(claims.acquire(PROMO, user, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.GATE_STALE);

        runtime.writeClaimGate(PROMO, true, 3, now() - 60_000, now() - 1);
        assertThat(claims.acquire(PROMO, user, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.OUT_OF_WINDOW);

        runtime.writeClaimGate(PROMO, true, 3, now() + 60_000, now() + 120_000);
        assertThat(claims.acquire(PROMO, user, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.OUT_OF_WINDOW);
        assertThat(claims.poolSize(PROMO)).isEqualTo(2);
    }

    @Test
    @DisplayName("claim acquisition: SPOP + claimed user + pending evidence; duplicate user; out of stock")
    void claimAcquisition() {
        runtime.writeClaimGate(PROMO, true, 3, now() - 1000, now() + 60_000);
        claims.addToPool(PROMO, java.util.List.of(11L, 12L));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();

        ClaimStore.AcquireResult first = claims.acquire(PROMO, alice, 3, 300_000);
        assertThat(first.code()).isEqualTo(ClaimStore.AcquireCode.ACQUIRED);
        assertThat(first.claimId()).isIn(11L, 12L);
        assertThat(first.gateRevision()).isEqualTo(3);
        assertThat(first.pending()).isNotNull();
        assertThat(first.pending().userId()).isEqualTo(alice);
        assertThat(first.pending().campaignRevision()).isEqualTo(3);
        assertThat(first.pending().acquiredAtMs()).isBetween(now() - 5_000, now() + 5_000);
        assertThat(claims.pending(PROMO, first.claimId())).isPresent();
        assertThat(claims.duePending(PROMO, now() + 400_000, 10)).containsExactly(first.claimId());
        assertThat(claims.duePending(PROMO, now(), 10)).isEmpty();

        ClaimStore.AcquireResult again = claims.acquire(PROMO, alice, 3, 300_000);
        assertThat(again.code()).isEqualTo(ClaimStore.AcquireCode.ALREADY_CLAIMED);
        assertThat(again.claimId()).isEqualTo(first.claimId());
        assertThat(again.pending()).isNotNull();

        assertThat(claims.acquire(PROMO, bob, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.ACQUIRED);
        assertThat(claims.acquire(PROMO, carol, 3, 300_000).code()).isEqualTo(ClaimStore.AcquireCode.OUT_OF_STOCK);

        claims.ack(PROMO, first.claimId());
        assertThat(claims.pending(PROMO, first.claimId())).isEmpty();
        ClaimStore.AcquireResult afterAck = claims.acquire(PROMO, alice, 3, 300_000);
        assertThat(afterAck.code()).isEqualTo(ClaimStore.AcquireCode.ALREADY_CLAIMED);
        assertThat(afterAck.pending()).isNull();
    }

    // ------------------------------------------------------------ reservation

    private ReservationStore.ReservationPayload payload(long orderId, long amount, String hash) {
        return new ReservationStore.ReservationPayload(orderId, PROMO, UUID.randomUUID(), 5L, amount,
                ReservationStore.STATE_RESERVED, now(), now() + 900_000, hash);
    }

    @Test
    @DisplayName("reserve: runtime missing, all-or-nothing counters, replay by hash, mismatch, exhausted")
    void reserve() {
        assertThat(reservations.reserve(payload(1, 1000, "h1"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.RUNTIME_MISSING);

        runtime.writeBudgetProjection(PROMO, 2L, 2500L, 0, 0, 0L, 0L);
        ReservationStore.ReserveResult first = reservations.reserve(payload(1, 1000, "h1"), 1_200_000);
        assertThat(first.code()).isEqualTo(ReservationStore.ReserveCode.RESERVED);
        assertThat(first.payload().state()).isEqualTo("RESERVED");
        RuntimeStore.RuntimeState state = runtime.read(PROMO).orElseThrow();
        assertThat(state.reservedQuantity()).isEqualTo(1);
        assertThat(state.reservedVnd()).isEqualTo(1000);

        assertThat(reservations.reserve(payload(1, 1000, "h1"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.REPLAY);
        assertThat(reservations.reserve(payload(1, 1500, "h2"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.PAYLOAD_MISMATCH);
        assertThat(runtime.read(PROMO).orElseThrow().reservedQuantity()).isEqualTo(1);

        // budget vnd: 1000 reserved + 2000 > 2500
        assertThat(reservations.reserve(payload(2, 2000, "h3"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.BUDGET_EXHAUSTED);
        assertThat(reservations.reserve(payload(2, 1500, "h4"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.RESERVED);
        // quantity: 2 of 2 reserved
        assertThat(reservations.reserve(payload(3, 1, "h5"), 1_200_000).code())
                .isEqualTo(ReservationStore.ReserveCode.BUDGET_EXHAUSTED);
        assertThat(reservations.indexedOrders(PROMO)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("confirm prepare fences RESERVED->CONFIRMING without moving counters; finalize moves reserved->used once")
    void prepareAndFinalize() {
        runtime.writeBudgetProjection(PROMO, null, null, 0, 0, 0L, 0L);
        reservations.reserve(payload(7, 4000, "h"), 1_200_000);

        ReservationStore.PrepareResult prepared = reservations.prepare(PROMO, 7, now() + 300_000, 1_500_000);
        assertThat(prepared.code()).isEqualTo(ReservationStore.PrepareCode.PREPARED);
        assertThat(prepared.payload().state()).isEqualTo("CONFIRMING");
        RuntimeStore.RuntimeState afterPrepare = runtime.read(PROMO).orElseThrow();
        assertThat(afterPrepare.reservedQuantity()).isEqualTo(1);
        assertThat(afterPrepare.usedQuantity()).isZero();

        assertThat(reservations.prepare(PROMO, 7, now() + 300_000, 1_500_000).code())
                .isEqualTo(ReservationStore.PrepareCode.CONFIRMING);
        assertThat(reservations.cancel(PROMO, 7, false)).isEqualTo(ReservationStore.CancelCode.CONFIRMING);
        assertThat(reservations.prepare(PROMO, 99, now() + 300_000, 1_500_000).code())
                .isEqualTo(ReservationStore.PrepareCode.MISSING);

        assertThat(reservations.finalize(PROMO, 7)).isEqualTo(ReservationStore.FinalizeCode.FINALIZED);
        RuntimeStore.RuntimeState afterFinalize = runtime.read(PROMO).orElseThrow();
        assertThat(afterFinalize.reservedQuantity()).isZero();
        assertThat(afterFinalize.reservedVnd()).isZero();
        assertThat(afterFinalize.usedQuantity()).isEqualTo(1);
        assertThat(afterFinalize.usedVnd()).isEqualTo(4000);
        assertThat(reservations.indexedOrders(PROMO)).isEmpty();

        // duplicate finalizer sau khi payload đã xóa: no-op, không increment mù
        assertThat(reservations.finalize(PROMO, 7)).isEqualTo(ReservationStore.FinalizeCode.MISSING);
        assertThat(runtime.read(PROMO).orElseThrow().usedQuantity()).isEqualTo(1);
        assertThat(reservations.cancel(PROMO, 7, false)).isEqualTo(ReservationStore.CancelCode.NO_RESERVATION);
    }

    @Test
    @DisplayName("cancel releases RESERVED, reports DRIFT instead of going negative, force releases CONFIRMING")
    void cancel() {
        runtime.writeBudgetProjection(PROMO, null, null, 0, 0, 0L, 0L);
        reservations.reserve(payload(8, 500, "h"), 1_200_000);
        assertThat(reservations.cancel(PROMO, 8, false)).isEqualTo(ReservationStore.CancelCode.RELEASED);
        assertThat(runtime.read(PROMO).orElseThrow().reservedQuantity()).isZero();

        reservations.reserve(payload(9, 500, "h9"), 1_200_000);
        redis.opsForHash().put(PromotionRedisKeys.runtime(PROMO), RuntimeStore.F_RESERVED_QTY, "0");
        assertThat(reservations.cancel(PROMO, 9, false)).isEqualTo(ReservationStore.CancelCode.DRIFT);
        assertThat(runtime.read(PROMO).orElseThrow().reservedQuantity()).isZero();

        reservations.reserve(payload(10, 500, "h10"), 1_200_000);
        reservations.prepare(PROMO, 10, now() + 1000, 1_500_000);
        assertThat(reservations.cancel(PROMO, 10, true)).isEqualTo(ReservationStore.CancelCode.RELEASED);
    }

    @Test
    @DisplayName("all keys of one promotion share the {promotion_id} hash tag")
    void hashTags() {
        assertThat(PromotionRedisKeys.runtime(PROMO)).isEqualTo("promo:{101}:runtime");
        assertThat(PromotionRedisKeys.reservation(PROMO, 5)).startsWith("promo:{101}:");
        assertThat(PromotionRedisKeys.pendingClaimsIndex(PROMO)).startsWith("promo:{101}:");
        assertThat(PromotionRedisKeys.claimPool(PROMO)).startsWith("promo:{101}:");
    }
}
