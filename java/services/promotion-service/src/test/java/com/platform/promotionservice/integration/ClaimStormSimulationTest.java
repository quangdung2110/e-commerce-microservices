package com.platform.promotionservice.integration;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.infrastructure.redis.ClaimStore;
import com.platform.promotionservice.infrastructure.redis.PromotionRedisKeys;
import com.platform.promotionservice.service.ClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mô phỏng flash sale (chỉ chạy khi {@code PROMOTION_CLAIM_STORM=true}): {@code USERS} người cùng bấm "Nhận voucher"
 * một lúc cho một voucher {@code LIMITED_CLAIM} chỉ có {@code QUOTA} suất, trên PostgreSQL + Redis thật.
 *
 * <p>Gọi thẳng {@link ClaimService} (bỏ qua tầng HTTP/Gateway), mọi request cùng được thả bằng một latch, mỗi request
 * một thread riêng nên số request đồng thời bằng số user (khắt khe hơn Tomcat 200 thread). Hikari giữ cấu hình thật
 * (16 connection). Số liệu in ra dòng {@code SIM|...}; chạy trên một máy, Docker, nên chỉ để kiểm đúng/ổn định,
 * không phải SLO.
 */
@EnabledIfEnvironmentVariable(named = "PROMOTION_CLAIM_STORM", matches = "true")
class ClaimStormSimulationTest extends AbstractPromotionIntegrationTest {

    private static final int USERS = intEnv("PROMOTION_STORM_USERS", 1000);
    private static final int QUOTA = intEnv("PROMOTION_STORM_QUOTA", 100);
    private static final int ROUNDS = intEnv("PROMOTION_STORM_ROUNDS", 5);
    private static final int CRASHED = intEnv("PROMOTION_STORM_CRASHED", 30);

    @Autowired
    ClaimService claims;
    @Autowired
    ClaimStore claimStore;
    @Autowired
    com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository wallets;

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("A: 1000 distinct users, 1 request each, quota 100 — repeated rounds")
    void oneRequestPerUser() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            Fixture f = fixture();
            List<UUID> users = users(USERS);
            Result r = storm(f, users, 1, Set.of());
            report("A-single-request/round" + round, f, r);
            assertInvariants(f, r, users);
            assertThat(r.created.get()).as("created").isEqualTo(QUOTA);
            assertThat(r.replay.get()).as("replay").isZero();
            assertThat(r.byError.getOrDefault(ErrorCode.OUT_OF_STOCK.name(), 0)).as("OUT_OF_STOCK").isEqualTo(USERS - QUOTA);
        }
    }

    @Test
    @DisplayName("B: every user double-clicks (2 simultaneous requests each)")
    void doubleClick() throws Exception {
        Fixture f = fixture();
        List<UUID> users = users(USERS);
        Result r = storm(f, users, 2, Set.of());
        report("B-double-click", f, r);
        assertInvariants(f, r, users);
        // Mỗi người thắng đúng một lần tạo; bản trùng chỉ replay hoặc bị từ chối, không bao giờ tạo thêm.
        assertThat(r.created.get()).as("created").isEqualTo(QUOTA);
    }

    @Test
    @DisplayName("C: API crashes right after SPOP for some users — pending sweeper must finish the job")
    void crashAfterSpop() throws Exception {
        Fixture f = fixture();
        List<UUID> users = users(USERS);
        Set<UUID> crashing = new HashSet<>(users.subList(0, CRASHED));
        Result r = storm(f, users, 1, crashing);
        report("C-crash-after-spop/before-sweep", f, r);

        long pendingBefore = redis.opsForHash().size(PromotionRedisKeys.pendingClaimsData(f.promotionId));
        assertThat(pendingBefore).as("pending left by crashed requests").isEqualTo(r.crashedAcquired.get());
        long walletsBefore = count("SELECT count(*) FROM user_coupon_wallets WHERE promotion_id = ?", f.promotionId);
        assertThat(walletsBefore).isEqualTo(r.created.get());

        int swept = claims.sweepPending(f.promotionId, Instant.now().plus(Duration.ofMinutes(10)), 1000);
        System.out.printf("SIM|scenario=C-crash-after-spop/sweep|pending_before=%d|swept=%d%n", pendingBefore, swept);
        assertThat(swept).isEqualTo((int) pendingBefore);

        // Sau sweep: đủ đúng QUOTA suất, mỗi suất đúng một ví, không còn pending.
        assertThat(count("SELECT count(*) FROM user_coupon_wallets WHERE promotion_id = ?", f.promotionId)).isEqualTo(QUOTA);
        assertThat(count("SELECT count(DISTINCT user_id) FROM user_coupon_wallets WHERE promotion_id = ?", f.promotionId))
                .isEqualTo(QUOTA);
        assertThat(count("SELECT claimed_quantity FROM promotion_budgets WHERE promotion_id = ?", f.promotionId)).isEqualTo(QUOTA);
        assertThat(count("SELECT count(*) FROM coupon_claims WHERE promotion_id = ? AND status = 'AVAILABLE'", f.promotionId))
                .isZero();
        assertThat(redis.opsForHash().size(PromotionRedisKeys.pendingClaimsData(f.promotionId))).isZero();
        assertThat(redis.opsForZSet().size(PromotionRedisKeys.pendingClaimsIndex(f.promotionId))).isZero();
        assertThat(claimStore.poolSize(f.promotionId)).isZero();
        assertThat(r.unexpected).as("unexpected exceptions").isEmpty();
    }

    @Test
    @DisplayName("D: where does the time go — each stage alone under the same 1000-way burst (no assertions)")
    void stageBreakdown() throws Exception {
        Fixture f = fixture();
        List<UUID> users = users(USERS);
        burst("redis-GET", users, user -> redis.opsForValue().get("sim:noop"));
        burst("db-select-1", users, user -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        burst("db-wallet-preflight(JPA)", users, user -> wallets.findByUserIdAndPromotionId(user, f.promotionId));
        burst("lua-claim-acquire", users, user -> claimStore.acquire(f.promotionId, user, f.revision, 300_000));

        // Cùng script, cùng thao tác, nhưng nạp từ chuỗi (không stat file mỗi lần gọi) trên một promotion mới.
        Fixture g = fixture();
        String text;
        try (var in = new org.springframework.core.io.ClassPathResource("lua/claim_acquire.lua").getInputStream()) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        org.springframework.data.redis.core.script.DefaultRedisScript<List> inline =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(text, List.class);
        List<String> keys = List.of(
                PromotionRedisKeys.runtime(g.promotionId), PromotionRedisKeys.claimPool(g.promotionId),
                PromotionRedisKeys.claimedUsers(g.promotionId), PromotionRedisKeys.pendingClaimsData(g.promotionId),
                PromotionRedisKeys.pendingClaimsIndex(g.promotionId));
        burst("lua-claim-acquire(inline-script)", users,
                user -> redis.execute(inline, keys, user.toString(), Long.toString(g.revision), "300000"));
    }

    private void burst(String label, List<UUID> users, java.util.function.Consumer<UUID> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID user : users) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    long t0 = System.nanoTime();
                    task.accept(user);
                    latencies.add(System.nanoTime() - t0);
                    return null;
                }));
            }
            ready.await(60, TimeUnit.SECONDS);
            long start = System.nanoTime();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
            double wallMs = (System.nanoTime() - start) / 1e6;
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            System.out.printf("SIM|stage=%s|n=%d|wall_ms=%.0f|p50_ms=%.1f|p95_ms=%.1f|p99_ms=%.1f|max_ms=%.1f%n", label,
                    users.size(), wallMs, pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), pct(sorted, 100));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- engine

    private record Fixture(long promotionId, long revision) {
    }

    private static final class Result {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger replay = new AtomicInteger();
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger crashedAcquired = new AtomicInteger();
        final Map<String, Integer> byError = new ConcurrentHashMap<>();
        final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        final Set<UUID> succeeded = ConcurrentHashMap.newKeySet();
        final Set<String> claimIdsSeen = ConcurrentHashMap.newKeySet();
        final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        long wallNanos;
        int requests;
    }

    private Fixture fixture() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        PromotionEntity promotion = givenPromotion(campaign, DistributionMode.LIMITED_CLAIM, (long) QUOTA);
        CampaignEntity published = publish(campaign);
        assertThat(claimStore.poolSize(promotion.getId())).isEqualTo(QUOTA);
        return new Fixture(promotion.getId(), published.getRevision());
    }

    private static List<UUID> users(int n) {
        List<UUID> users = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            users.add(UUID.randomUUID());
        }
        return users;
    }

    /**
     * Thả {@code users × perUser} request cùng lúc. User trong {@code crashing} giả lập API chết ngay sau SPOP:
     * chỉ chạy Lua acquire, không persist (pending evidence ở lại Redis).
     */
    private Result storm(Fixture f, List<UUID> users, int perUser, Set<UUID> crashing) throws Exception {
        Result result = new Result();
        int total = users.size() * perUser;
        result.requests = total;
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(total);
        List<Future<?>> futures = new ArrayList<>(total);
        try {
            for (UUID user : users) {
                for (int i = 0; i < perUser; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        long t0 = System.nanoTime();
                        try {
                            if (crashing.contains(user)) {
                                ClaimStore.AcquireResult a = claimStore.acquire(f.promotionId, user, f.revision, 300_000);
                                if (a.code() == ClaimStore.AcquireCode.ACQUIRED) {
                                    result.crashedAcquired.incrementAndGet();
                                } else if (a.code() == ClaimStore.AcquireCode.OUT_OF_STOCK) {
                                    result.byError.merge(ErrorCode.OUT_OF_STOCK.name(), 1, Integer::sum);
                                } else {
                                    result.unexpected.add("crash-path " + a.code());
                                }
                            } else {
                                classify(result, user, claims.claim(user, f.promotionId, f.revision));
                            }
                        } catch (DomainException e) {
                            result.byError.merge(e.errorCode().name(), 1, Integer::sum);
                        } catch (Throwable t) {
                            result.unexpected.add(t.getClass().getSimpleName() + ": " + t.getMessage());
                        } finally {
                            result.latenciesNanos.add(System.nanoTime() - t0);
                        }
                        return null;
                    }));
                }
            }
            assertThat(ready.await(60, TimeUnit.SECONDS)).as("all request threads ready").isTrue();
            long start = System.nanoTime();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
            result.wallNanos = System.nanoTime() - start;
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private void classify(Result result, UUID user, ClaimService.Outcome outcome) {
        if (outcome instanceof ClaimService.Committed committed) {
            result.succeeded.add(user);
            result.claimIdsSeen.add(committed.response().claimId());
            if (committed.created()) {
                result.created.incrementAndGet();
            } else {
                result.replay.incrementAndGet();
            }
        } else {
            result.pending.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- invariants & report

    /** Bất biến sau storm khi không có pending/lỗi lạ: đúng QUOTA suất, đúng QUOTA ví, không ai nhận hai lần. */
    private void assertInvariants(Fixture f, Result r, List<UUID> users) {
        long id = f.promotionId;
        assertThat(r.unexpected).as("unexpected exceptions").isEmpty();
        assertThat(r.pending.get()).as("CLAIM_PENDING responses").isZero();
        assertThat(r.byError.keySet()).as("only 'sold out' rejections are allowed")
                .isSubsetOf(ErrorCode.OUT_OF_STOCK.name());

        assertThat(count("SELECT count(*) FROM user_coupon_wallets WHERE promotion_id = ?", id)).as("wallets").isEqualTo(QUOTA);
        assertThat(count("SELECT count(DISTINCT user_id) FROM user_coupon_wallets WHERE promotion_id = ?", id))
                .as("distinct wallet owners").isEqualTo(QUOTA);
        assertThat(count("SELECT count(*) FROM coupon_claims WHERE promotion_id = ? AND status = 'CLAIMED'", id))
                .as("CLAIMED rows").isEqualTo(QUOTA);
        assertThat(count("SELECT count(DISTINCT owner_id) FROM coupon_claims WHERE promotion_id = ? AND status = 'CLAIMED'", id))
                .as("distinct claim owners").isEqualTo(QUOTA);
        assertThat(count("SELECT count(*) FROM coupon_claims WHERE promotion_id = ? AND status = 'AVAILABLE'", id))
                .as("AVAILABLE rows left").isZero();
        assertThat(count("SELECT claimed_quantity FROM promotion_budgets WHERE promotion_id = ?", id))
                .as("claimed_quantity").isEqualTo(QUOTA);
        assertThat(count("""
                SELECT count(*) FROM user_coupon_wallets w JOIN coupon_claims c ON c.id = w.coupon_claim_id
                WHERE w.promotion_id = ? AND c.owner_id = w.user_id AND c.claimed_campaign_revision = ?
                """, id, f.revision)).as("wallet↔claim consistent with revision at acquisition").isEqualTo(QUOTA);

        assertThat(r.succeeded).as("users told 'success'").hasSize(QUOTA);
        assertThat(r.claimIdsSeen).as("distinct claim ids handed out").hasSize(QUOTA);
        Set<UUID> owners = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT user_id FROM user_coupon_wallets WHERE promotion_id = ?", UUID.class, id));
        assertThat(owners).as("success set == wallet owner set").isEqualTo(r.succeeded);
        assertThat(users).containsAll(owners);

        assertThat(claimStore.poolSize(id)).as("redis pool").isZero();
        assertThat(redis.opsForHash().size(PromotionRedisKeys.pendingClaimsData(id))).as("redis pending data").isZero();
        assertThat(redis.opsForZSet().size(PromotionRedisKeys.pendingClaimsIndex(id))).as("redis pending index").isZero();
        assertThat(redis.opsForHash().size(PromotionRedisKeys.claimedUsers(id))).as("redis claimed_users").isEqualTo(QUOTA);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void report(String scenario, Fixture f, Result r) {
        List<Long> sorted = new ArrayList<>(r.latenciesNanos);
        Collections.sort(sorted);
        Map<String, Integer> errors = new TreeMap<>(r.byError);
        double wallMs = r.wallNanos / 1e6;
        System.out.printf("SIM|scenario=%s|promotion=%d|users=%d|quota=%d|requests=%d|created=%d|replay=%d|pending=%d"
                        + "|crashed_acquired=%d|errors=%s|unexpected=%d|wall_ms=%.0f|rps=%.0f"
                        + "|p50_ms=%.1f|p95_ms=%.1f|p99_ms=%.1f|max_ms=%.1f%n",
                scenario, f.promotionId, USERS, QUOTA, r.requests, r.created.get(), r.replay.get(), r.pending.get(),
                r.crashedAcquired.get(), errors, r.unexpected.size(), wallMs, r.requests / (wallMs / 1000.0),
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), pct(sorted, 100));
        if (!r.unexpected.isEmpty()) {
            System.out.println("SIM|unexpected=" + r.unexpected.stream().distinct().limit(5).toList());
        }
    }

    private static double pct(List<Long> sortedNanos, int p) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        int idx = Math.min(sortedNanos.size() - 1, (int) Math.ceil(p / 100.0 * sortedNanos.size()) - 1);
        return sortedNanos.get(Math.max(idx, 0)) / 1e6;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
