package com.platform.promotionservice.integration;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import com.platform.promotionservice.infrastructure.redis.ScheduleIndex;
import com.platform.promotionservice.service.CampaignScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-021 trên PostgreSQL + Redis thật: START/END phát theo lô (mỗi lô một transaction), lô lỗi không chặn lô
 * sau và không làm mất phần đã commit, retry không phát trùng cũng không tốn lock/sequence nhờ dedup pre-check.
 * Lô nhỏ (3 rule) để 7 product thành 3 lô; "poison product" là trigger PostgreSQL thật làm insert outbox thất bại.
 */
class CampaignScheduleBatchIntegrationTest extends AbstractPromotionIntegrationTest {

    private static final int BATCH = 3;
    private static final int PRODUCTS = 7; // lô: [1,2,3] [4,5,6] [7]

    @Autowired
    private CampaignScheduleService scheduler;
    @Autowired
    private ScheduleIndex schedule;
    @Autowired
    private PromotionProperties properties;

    private int originalBatchSize;

    @BeforeEach
    void smallBatches() {
        originalBatchSize = properties.getSchedule().getEmitBatchSize();
        properties.getSchedule().setEmitBatchSize(BATCH);
        dropPoison();
    }

    @AfterEach
    void restore() {
        dropPoison();
        properties.getSchedule().setEmitBatchSize(originalBatchSize);
    }

    @Test
    @DisplayName("START của campaign 7 product, lô 3: phát đủ 7 snapshot ACTIVE, rồi dọn member")
    void startEmitsEveryProductAcrossBatches() {
        PublishedCampaign published = publishedCampaign();
        dueStart(published.campaign());

        assertThat(scheduler.processDue()).isEqualTo(1);

        assertThat(startRows()).isEqualTo(PRODUCTS);
        assertThat(distinctStartProducts()).isEqualTo(PRODUCTS);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM promotion_outbox_events
                WHERE dedup_key LIKE '%:trigger:START' AND payload->'data'->>'state' = 'ACTIVE'
                """, Long.class)).isEqualTo(PRODUCTS);
        assertThat(schedule.contains(published.campaign().getId(), revisionOf(published), ScheduleTrigger.START)).isFalse();
    }

    @Test
    @DisplayName("một lô lỗi: lô trước/sau vẫn commit, member được giữ; vòng sau chỉ phát phần thiếu và không tốn sequence cho phần đã có")
    void poisonBatchIsIsolatedAndRetryOnlyEmitsMissingProducts() {
        PublishedCampaign published = publishedCampaign();
        UUID poison = published.products().get(3); // rule thứ 4: đầu lô 2
        dueStart(published.campaign());
        createPoisonTrigger(poison);

        assertThat(scheduler.processDue()).as("member chưa hoàn tất nên không tính là processed").isZero();

        // Lô 1 (3 product) và lô 3 (1 product) đã commit; lô 2 rollback toàn bộ kể cả 2 product không lỗi.
        assertThat(startRows()).isEqualTo(4);
        assertThat(startProducts()).containsExactlyInAnyOrder(
                published.products().get(0), published.products().get(1), published.products().get(2), published.products().get(6));
        assertThat(schedule.contains(published.campaign().getId(), revisionOf(published), ScheduleTrigger.START))
                .as("member phải còn trong ZSET để retry").isTrue();

        dropPoison();
        long sequenceBeforeRetry = markdownSequence();

        assertThat(scheduler.processDue()).isEqualTo(1);

        assertThat(startRows()).isEqualTo(PRODUCTS);
        assertThat(distinctStartProducts()).as("không có product nào bị phát hai lần").isEqualTo(PRODUCTS);
        assertThat(markdownSequence() - sequenceBeforeRetry)
                .as("chỉ 3 product của lô 2 cần lock/tính winner/cấp revision; 4 product đã có bị pre-check bỏ qua")
                .isEqualTo(3);
        assertThat(schedule.contains(published.campaign().getId(), revisionOf(published), ScheduleTrigger.START)).isFalse();
    }

    @Test
    @DisplayName("chạy lại member đã hoàn tất: không thêm row, không tốn sequence")
    void rerunningCompletedMemberIsANoOp() {
        PublishedCampaign published = publishedCampaign();
        dueStart(published.campaign());
        assertThat(scheduler.processDue()).isEqualTo(1);
        long rows = startRows();
        long sequence = markdownSequence();

        dueStart(published.campaign()); // worker khác/replay từ fallback thêm lại cùng member
        assertThat(scheduler.processDue()).isEqualTo(1);

        assertThat(startRows()).isEqualTo(rows);
        assertThat(markdownSequence()).isEqualTo(sequence);
        assertThat(schedule.contains(published.campaign().getId(), revisionOf(published), ScheduleTrigger.START)).isFalse();
    }

    @Test
    @DisplayName("hai worker xử lý cùng một member đồng thời: mỗi product đúng một snapshot")
    void concurrentWorkersEmitEachProductExactlyOnce() throws Exception {
        PublishedCampaign published = publishedCampaign();
        dueStart(published.campaign());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> run = () -> {
                    go.await();
                    return scheduler.processDue();
                };
                runs.add(pool.submit(run));
            }
            go.countDown();
            for (Future<Integer> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(startRows()).isEqualTo(PRODUCTS);
        assertThat(distinctStartProducts()).isEqualTo(PRODUCTS);
        assertThat(schedule.contains(published.campaign().getId(), revisionOf(published), ScheduleTrigger.START)).isFalse();
    }

    @Test
    @DisplayName("member mang revision cũ: dọn member, không phát gì")
    void staleRevisionMemberEmitsNothing() {
        PublishedCampaign published = publishedCampaign();
        long staleRevision = revisionOf(published) - 1;
        schedule.add(published.campaign().getId(), staleRevision, ScheduleTrigger.START, System.currentTimeMillis() - 1000);

        assertThat(scheduler.processDue()).isZero();

        assertThat(startRows()).isZero();
        assertThat(schedule.contains(published.campaign().getId(), staleRevision, ScheduleTrigger.START)).isFalse();
    }

    // ------------------------------------------------------------------ fixtures

    private record PublishedCampaign(CampaignEntity campaign, List<UUID> products) {
    }

    /** Campaign đang ACTIVE với 7 rule theo thứ tự tạo (rule ID tăng dần), đã publish (admin path phát PUBLISH). */
    private PublishedCampaign publishedCampaign() {
        CampaignEntity campaign = givenActiveWindowCampaign(Duration.ofHours(2));
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < PRODUCTS; i++) {
            UUID product = UUID.randomUUID();
            products.add(product);
            givenMarkdownRule(campaign, product, 5);
        }
        givenPromotion(campaign, DistributionMode.PUBLIC_SAVE, null);
        return new PublishedCampaign(publish(campaign), products);
    }

    private long revisionOf(PublishedCampaign published) {
        return currentRevision(published.campaign().getId());
    }

    /** START đã đến hạn: mô phỏng ZSET trả member vì boundary đã qua. */
    private void dueStart(CampaignEntity campaign) {
        schedule.add(campaign.getId(), currentRevision(campaign.getId()), ScheduleTrigger.START, System.currentTimeMillis() - 1000);
    }

    private long startRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM promotion_outbox_events WHERE dedup_key LIKE '%:trigger:START'", Long.class);
    }

    private long distinctStartProducts() {
        return jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT partition_key) FROM promotion_outbox_events WHERE dedup_key LIKE '%:trigger:START'",
                Long.class);
    }

    private List<UUID> startProducts() {
        return jdbcTemplate.queryForList(
                "SELECT partition_key FROM promotion_outbox_events WHERE dedup_key LIKE '%:trigger:START'", String.class)
                .stream().map(UUID::fromString).toList();
    }

    private long markdownSequence() {
        return jdbcTemplate.queryForObject("SELECT last_value FROM markdown_source_revision_seq", Long.class);
    }

    /** Insert outbox START của đúng product này bị PostgreSQL từ chối thật (không mock). */
    private void createPoisonTrigger(UUID product) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION it_fail_markdown_emit() RETURNS trigger AS $$
                BEGIN
                  IF NEW.partition_key = TG_ARGV[0] THEN
                    RAISE EXCEPTION 'simulated poison product';
                  END IF;
                  RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("CREATE TRIGGER it_fail_markdown_emit BEFORE INSERT ON promotion_outbox_events "
                + "FOR EACH ROW WHEN (NEW.dedup_key LIKE '%:trigger:START') EXECUTE FUNCTION it_fail_markdown_emit('"
                + product + "')");
    }

    private void dropPoison() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS it_fail_markdown_emit ON promotion_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS it_fail_markdown_emit()");
    }
}
