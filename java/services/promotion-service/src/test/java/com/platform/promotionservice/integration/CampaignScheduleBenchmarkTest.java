package com.platform.promotionservice.integration;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.redis.ScheduleIndex;
import com.platform.promotionservice.service.CampaignScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Benchmark ADR-021 (chỉ chạy khi {@code PROMOTION_BENCH=true}): so sánh cách cũ (một transaction cho cả campaign,
 * không pre-check) với cách mới (theo lô + dedup pre-check) trên PostgreSQL + Redis thật.
 *
 * <p>"Cách cũ" được dựng lại đúng như code trước ADR-021: {@code emitForCampaign} bên trong một
 * {@code TransactionTemplate}. Số liệu in ra dòng {@code BENCH|...}. Đây là đo trên một máy, một lần chạy mỗi
 * kịch bản, Postgres chưa tinh chỉnh trong Docker: dùng để so sánh tương đối, không phải SLO.
 */
@EnabledIfEnvironmentVariable(named = "PROMOTION_BENCH", matches = "true")
class CampaignScheduleBenchmarkTest extends AbstractPromotionIntegrationTest {

    private static final int PRODUCTS = Integer.parseInt(System.getenv().getOrDefault("PROMOTION_BENCH_PRODUCTS", "10000"));
    private static final AtomicBoolean WARMED = new AtomicBoolean();

    @Autowired
    private CampaignScheduleService scheduler;
    @Autowired
    private ScheduleIndex schedule;
    @Autowired
    private PromotionProperties properties;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PromotionJdbc promotionJdbc;
    @Autowired
    private CampaignRepository campaigns;

    private int originalBatchSize;

    @BeforeEach
    void setUp() {
        originalBatchSize = properties.getSchedule().getEmitBatchSize();
        dropPoison();
    }

    @AfterEach
    void tearDown() {
        dropPoison();
        properties.getSchedule().setEmitBatchSize(originalBatchSize);
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("S1: START lạnh cho N product + chạy lại (retry) — cũ vs mới theo cỡ lô")
    void coldEmissionAndRetry() {
        warmUp();
        Fixture f = fixture(PRODUCTS);

        reset();
        Run oldCold = measure("S1 cũ: 1 transaction", f, () -> oldPath(f));
        Run oldRetry = measure("S1 cũ: chạy lại (đã phát đủ)", f, () -> oldPath(f));
        report(oldCold);
        report(oldRetry);

        for (int batch : new int[]{200, 50, 1000}) {
            reset();
            Run cold = measure("S1 mới lô=" + batch + ": lần đầu", f, () -> newPath(f, batch));
            report(cold);
            if (batch == 200) {
                Run retry = measure("S1 mới lô=200: chạy lại (đã phát đủ)", f, () -> newPath(f, 200));
                report(retry);
            }
        }
    }

    @Test
    @DisplayName("S2: một product lỗi ở ~99% danh sách — cũ rollback tất cả, mới chỉ mất một lô")
    void lateFailure() {
        warmUp();
        Fixture f = fixture(PRODUCTS);
        UUID poison = f.products().get((int) (PRODUCTS * 0.99) - 1);

        reset();
        createPoisonTrigger(poison);
        Run oldFail = measure("S2 cũ: lần đầu (product lỗi ở ~99%)", f, () -> oldPath(f));
        dropPoison();
        Run oldRetry = measure("S2 cũ: retry sau khi sửa lỗi", f, () -> oldPath(f));
        report(oldFail);
        report(oldRetry);
        total("S2 cũ", oldFail, oldRetry);

        reset();
        createPoisonTrigger(poison);
        Run newFail = measure("S2 mới lô=200: lần đầu (product lỗi ở ~99%)", f, () -> newPath(f, 200));
        dropPoison();
        Run newRetry = measure("S2 mới lô=200: retry sau khi sửa lỗi", f, () -> newPath(f, 200));
        report(newFail);
        report(newRetry);
        total("S2 mới", newFail, newRetry);
    }

    @Test
    @DisplayName("S2b: như S2 nhưng chỉ cách mới (chạy lại riêng khi số đo wall-clock của lần trước bị nhiễu)")
    void lateFailureBatchedOnly() {
        warmUp();
        Fixture f = fixture(PRODUCTS);
        UUID poison = f.products().get((int) (PRODUCTS * 0.99) - 1);

        reset();
        createPoisonTrigger(poison);
        Run newFail = measure("S2b mới lô=200: lần đầu (product lỗi ở ~99%)", f, () -> newPath(f, 200));
        dropPoison();
        Run newRetry = measure("S2b mới lô=200: retry sau khi sửa lỗi", f, () -> newPath(f, 200));
        report(newFail);
        report(newRetry);
        total("S2b mới", newFail, newRetry);
    }

    // ------------------------------------------------------------------ paths

    /** Đúng code trước ADR-021: cả campaign trong một transaction, không pre-check. */
    private String oldPath(Fixture f) {
        try {
            tx.execute(status -> {
                CampaignEntity campaign = campaigns.findById(f.campaignId()).orElseThrow();
                scheduler.emitForCampaign(campaign, campaign.getRevision(), ScheduleTrigger.START);
                return null;
            });
            return "OK";
        } catch (RuntimeException e) {
            return "FAILED";
        }
    }

    private String newPath(Fixture f, int batchSize) {
        properties.getSchedule().setEmitBatchSize(batchSize);
        schedule.add(f.campaignId(), currentRevision(f.campaignId()), ScheduleTrigger.START, System.currentTimeMillis() - 1000);
        scheduler.processDue();
        return schedule.contains(f.campaignId(), currentRevision(f.campaignId()), ScheduleTrigger.START) ? "INCOMPLETE" : "OK";
    }

    // ------------------------------------------------------------------ measurement

    private record Fixture(long campaignId, List<UUID> products) {
    }

    private record Run(String label, String outcome, long millis, long rows, long sequenceUsed, double longestTxSeconds,
                       int maxAdvisoryLocks, int probes, double waitP50Ms, double waitP95Ms, double waitMaxMs) {
    }

    private Run measure(String label, Fixture f, java.util.function.Supplier<String> work) {
        long seqBefore = markdownSequence();
        Monitor monitor = new Monitor(f.products());
        monitor.start();
        long t0 = System.nanoTime();
        String outcome = work.get();
        long millis = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        monitor.stop();
        long rows = startRows();
        return new Run(label, outcome, millis, rows, markdownSequence() - seqBefore,
                monitor.longestTx, monitor.maxLocks, monitor.waits.size(), monitor.percentile(50), monitor.percentile(95),
                monitor.percentile(100));
    }

    /** Lấy mẫu pg_stat_activity/pg_locks và đo thời gian chờ lock của một "writer khác" cùng lúc với emission. */
    private final class Monitor {
        private final List<UUID> products;
        private volatile boolean running = true;
        private volatile double longestTx;
        private volatile int maxLocks;
        private final List<Double> waits = Collections.synchronizedList(new ArrayList<>());
        private Thread sampler;
        private Thread probe;

        Monitor(List<UUID> products) {
            this.products = products;
        }

        void start() {
            sampler = new Thread(() -> {
                while (running) {
                    try {
                        Double age = jdbcTemplate.queryForObject("""
                                SELECT coalesce(max(extract(epoch FROM (clock_timestamp() - xact_start))), 0)
                                FROM pg_stat_activity
                                WHERE datname = current_database() AND pid <> pg_backend_pid()
                                  AND xact_start IS NOT NULL AND state IN ('active', 'idle in transaction')
                                  AND coalesce(wait_event_type, '') <> 'Lock'
                                """, Double.class);
                        Integer locks = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted", Integer.class);
                        if (age != null && age > longestTx) {
                            longestTx = age;
                        }
                        if (locks != null && locks > maxLocks) {
                            maxLocks = locks;
                        }
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException ignored) {
                        // lấy mẫu best-effort
                    }
                }
            }, "bench-sampler");
            probe = new Thread(() -> {
                Random random = new Random(42);
                while (running) {
                    UUID target = products.get(random.nextInt(products.size()));
                    long t0 = System.nanoTime();
                    try {
                        tx.execute(status -> {
                            promotionJdbc.lockProduct(target);
                            return null;
                        });
                        waits.add((System.nanoTime() - t0) / 1_000_000.0);
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException ignored) {
                        // probe best-effort
                    }
                }
            }, "bench-probe");
            sampler.setDaemon(true);
            probe.setDaemon(true);
            sampler.start();
            probe.start();
        }

        void stop() {
            running = false;
            try {
                sampler.join(5000);
                probe.join(60000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        double percentile(int p) {
            List<Double> copy;
            synchronized (waits) {
                copy = new ArrayList<>(waits);
            }
            if (copy.isEmpty()) {
                return 0;
            }
            Collections.sort(copy);
            int index = (int) Math.min(copy.size() - 1, Math.ceil(p / 100.0 * copy.size()) - 1);
            return copy.get(Math.max(index, 0));
        }
    }

    private void report(Run r) {
        System.out.printf("BENCH|%s|outcome=%s|time=%dms|start_rows_total=%d|sequence_used=%d|longest_open_tx=%.2fs"
                        + "|max_advisory_locks_held=%d|lock_wait_probes=%d|wait_p50=%.1fms|wait_p95=%.1fms|wait_max=%.1fms%n",
                r.label(), r.outcome(), r.millis(), r.rows(), r.sequenceUsed(), r.longestTxSeconds(), r.maxAdvisoryLocks(),
                r.probes(), r.waitP50Ms(), r.waitP95Ms(), r.waitMaxMs());
    }

    private void total(String label, Run first, Run second) {
        System.out.printf("BENCH|%s TỔNG để hội tụ đủ %d product|time=%dms (%d + %d)%n", label, PRODUCTS,
                first.millis() + second.millis(), first.millis(), second.millis());
    }

    // ------------------------------------------------------------------ fixtures

    /** Campaign ACTIVE đã publish rồi mới bulk-insert N rule bằng SQL (nhanh hơn N lệnh admin). */
    private Fixture fixture(int products) {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        CampaignEntity published = publish(campaign);
        jdbcTemplate.update("""
                INSERT INTO markdown_rules (campaign_id, product_id, discount_type, discount_value, priority)
                SELECT ?, gen_random_uuid(), 'PERCENTAGE', 1500, 5 FROM generate_series(1, ?)
                """, published.getId(), products);
        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT product_id FROM markdown_rules WHERE campaign_id = ? ORDER BY id", UUID.class, published.getId());
        return new Fixture(published.getId(), ids);
    }

    private void warmUp() {
        if (!WARMED.compareAndSet(false, true)) {
            return;
        }
        Fixture f = fixture(500);
        reset();
        oldPath(f);
        reset();
        newPath(f, 200);
        reset();
        jdbcTemplate.execute("TRUNCATE markdown_rules, campaigns RESTART IDENTITY CASCADE");
    }

    /** Xoá outbox + đưa sequence về đầu + dọn ZSET để mỗi lần đo bắt đầu từ cùng trạng thái. */
    private void reset() {
        jdbcTemplate.execute("TRUNCATE promotion_outbox_events");
        jdbcTemplate.execute("ALTER SEQUENCE markdown_source_revision_seq RESTART");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private long startRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM promotion_outbox_events WHERE dedup_key LIKE '%:trigger:START'", Long.class);
    }

    private long markdownSequence() {
        return jdbcTemplate.queryForObject("SELECT last_value FROM markdown_source_revision_seq", Long.class);
    }

    private void createPoisonTrigger(UUID product) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION bench_fail_markdown_emit() RETURNS trigger AS $$
                BEGIN
                  IF NEW.partition_key = TG_ARGV[0] THEN
                    RAISE EXCEPTION 'simulated poison product';
                  END IF;
                  RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("CREATE TRIGGER bench_fail_markdown_emit BEFORE INSERT ON promotion_outbox_events "
                + "FOR EACH ROW WHEN (NEW.dedup_key LIKE '%:trigger:START') EXECUTE FUNCTION bench_fail_markdown_emit('"
                + product + "')");
    }

    private void dropPoison() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS bench_fail_markdown_emit ON promotion_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS bench_fail_markdown_emit()");
    }
}
