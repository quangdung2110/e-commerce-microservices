package com.platform.promotionservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.rule.ConditionNode;
import com.platform.promotionservice.domain.rule.LogicalNode;
import com.platform.promotionservice.domain.rule.RuleFields;
import com.platform.promotionservice.domain.rule.RuleOperator;
import com.platform.promotionservice.service.CampaignAdminService;
import com.platform.promotionservice.service.MarkdownAdminService;
import com.platform.promotionservice.service.PromotionAdminService;
import com.platform.promotionservice.web.dto.CampaignDtos;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MarkdownDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import com.platform.promotionservice.web.dto.PromotionDtos;
import com.platform.promotionservice.web.dto.ValidityDto;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nền chung cho integration test: PostgreSQL + Redis thật qua Testcontainers; schema do
 * Flyway của service tạo ({@code ddl-auto: validate}). Kafka listener và workers tắt
 * trong profile test; consumer/worker logic được gọi trực tiếp.
 *
 * <p>Đặt {@code TEST_DB_URL} / {@code TEST_REDIS_HOST}+{@code TEST_REDIS_PORT} để dùng
 * dependency có sẵn thay vì Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractPromotionIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");
    private static final String EXTERNAL_REDIS_HOST = System.getenv("TEST_REDIS_HOST");

    /** Mạng chung + alias cố định để Debezium (OutboxCdcIntegrationTest) với tới PostgreSQL từ trong Docker. */
    static final Network NETWORK = Network.newNetwork();
    static final String POSTGRES_ALIAS = "promotion-postgres";

    static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_DB_URL == null
            ? new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("promotion_service")
            .withNetwork(NETWORK).withNetworkAliases(POSTGRES_ALIAS)
            // Outbox CDC cần logical decoding, đúng như docker-compose.
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4",
                    "-c", "max_wal_senders=4")
            : null;
    private static final RedisContainer REDIS = EXTERNAL_REDIS_HOST == null
            ? new RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
            : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
        if (REDIS != null) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "postgres"));
        }
        if (REDIS != null) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        } else {
            registry.add("spring.data.redis.host", () -> EXTERNAL_REDIS_HOST);
            registry.add("spring.data.redis.port", () -> env("TEST_REDIS_PORT", "6379"));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final AtomicInteger CODES = new AtomicInteger();

    @Autowired
    protected CampaignAdminService campaignAdmin;
    @Autowired
    protected PromotionAdminService promotionAdmin;
    @Autowired
    protected MarkdownAdminService markdownAdmin;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected ObjectMapper objectMapper;

    protected static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000ad01");

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE promotion_outbox_events, promotion_command_receipts, user_coupon_wallets, coupon_claims,
                         promotion_budgets, promotions, markdown_rules, catalog_product_tombstones, campaigns
                RESTART IDENTITY CASCADE
                """);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        CallerContext.set(ADMIN, List.of("ADMIN"), null, "req-" + UUID.randomUUID(), null);
    }

    @AfterEach
    void clearContext() {
        CallerContext.clear();
    }

    // ---------------------------------------------------------------- fixtures

    protected static String uniqueCode(String prefix) {
        return prefix + "-" + CODES.incrementAndGet();
    }

    protected CampaignEntity givenDraftCampaign(Instant start, Instant end) {
        return campaignAdmin.create(new CampaignDtos.CreateCampaignRequest(uniqueCode("CAMP"), "Campaign", null, start, end));
    }

    /** Campaign đang ACTIVE: bắt đầu 1 giờ trước, kết thúc sau {@code duration}. */
    protected CampaignEntity givenActiveWindowCampaign(Duration duration) {
        Instant now = Instant.now();
        return givenDraftCampaign(now.minus(Duration.ofHours(1)), now.plus(duration));
    }

    protected PromotionDtos.CreatePromotionRequest promotionRequest(DistributionMode mode, Long totalClaimQuantity) {
        return new PromotionDtos.CreatePromotionRequest(
                uniqueCode("PROMO"), "Ten percent", null, mode,
                new DiscountDto.Percentage("PERCENTAGE", 1000, MoneyDto.vnd(50000)),
                MoneyDto.vnd(100000),
                LogicalNode.and(ConditionNode.of(RuleOperator.GREATER_THAN_OR_EQUAL, RuleFields.CART_TOTAL_AMOUNT, 100000)),
                1,
                new ValidityDto.CampaignEnd("CAMPAIGN_END"),
                true,
                new PromotionDtos.BudgetDefinition(totalClaimQuantity, 10L, MoneyDto.vnd(1_000_000)));
    }

    protected PromotionEntity givenPromotion(CampaignEntity campaign, DistributionMode mode, Long totalClaimQuantity) {
        return promotionAdmin.create(Long.toString(campaign.getId()), etag(campaign),
                promotionRequest(mode, totalClaimQuantity)).promotion();
    }

    protected MarkdownDtos.MarkdownRuleResponse givenMarkdownRule(CampaignEntity campaign, UUID productId, int priority) {
        return markdownAdmin.create(Long.toString(campaign.getId()), etag(campaign),
                new MarkdownDtos.CreateMarkdownRuleRequest(productId,
                        new DiscountDto.Percentage("PERCENTAGE", 1500, null), priority, true)).toResponse();
    }

    protected CampaignEntity publish(CampaignEntity campaign) {
        return transition(campaign, PublicationStatus.PUBLISHED);
    }

    protected CampaignEntity transition(CampaignEntity campaign, PublicationStatus target) {
        return campaignAdmin.transition(Long.toString(campaign.getId()), etag(campaign),
                new CampaignDtos.CampaignTransitionRequest(target, "test"));
    }

    protected String etag(CampaignEntity campaign) {
        return CampaignEntity.etagOf(currentRevision(campaign.getId()));
    }

    protected long currentRevision(long campaignId) {
        return jdbcTemplate.queryForObject("SELECT revision FROM campaigns WHERE id = ?", Long.class, campaignId);
    }

    /** Context của Checkout/Order (TrustedCheckoutContext): channel luôn CHECKOUT. */
    protected CheckoutDtos.CheckoutContext context(long subtotalVnd) {
        return context(subtotalVnd, "CHECKOUT");
    }

    protected CheckoutDtos.CheckoutContext context(long subtotalVnd, String channel) {
        return new CheckoutDtos.CheckoutContext(MoneyDto.vnd(subtotalVnd), "STRIPE", channel,
                List.of(new CheckoutDtos.CheckoutItem(UUID.randomUUID(), "SKU-1", 1, MoneyDto.vnd(subtotalVnd),
                        List.of("cat-1"))), Map.of());
    }

    // ------------------------------------------------------------------ outbox

    protected record OutboxRow(String eventType, String partitionKey, long sourceRevision, String dedupKey, String state,
                               String inactiveReason) {
    }

    protected List<OutboxRow> outboxRows() {
        return jdbcTemplate.query("""
                SELECT event_type, partition_key, source_revision, dedup_key,
                       payload->'data'->>'state' AS state, payload->'data'->>'inactive_reason' AS reason
                FROM promotion_outbox_events ORDER BY source_revision
                """, (rs, i) -> new OutboxRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getString(6)));
    }
}
