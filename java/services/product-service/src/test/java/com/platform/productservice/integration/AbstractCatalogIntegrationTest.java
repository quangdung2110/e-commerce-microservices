package com.platform.productservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.infrastructure.persistence.MarkdownProjectionRepository;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.AttributeService;
import com.platform.productservice.service.CategoryCommandService;
import com.platform.productservice.service.MediaCommandService;
import com.platform.productservice.service.ProductCommandService;
import com.platform.productservice.service.SkuCommandService;
import com.platform.productservice.web.dto.AttributeDtos;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nền chung cho integration test: PostgreSQL thật qua Testcontainers, schema do
 * chính migration Flyway của service tạo ra, nên test cũng xác nhận migration
 * khớp entity ({@code ddl-auto: validate}).
 *
 * <p>Kafka listener bị tắt trong profile test; luồng consumer được gọi trực tiếp
 * để không cần broker. Việc chụp replay offsets của export cũng đi qua
 * {@link StubCatalogOffsetProvider} vì lý do đó — hành vi với broker thật được
 * kiểm bằng smoke test riêng.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AbstractCatalogIntegrationTest.OffsetProviderConfiguration.class)
abstract class AbstractCatalogIntegrationTest {

    protected static final String PRODUCT_TOPIC = "t.product.events";
    protected static final String CATEGORY_TOPIC = "t.category.events";

    @TestConfiguration
    static class OffsetProviderConfiguration {

        @Bean
        @Primary
        StubCatalogOffsetProvider stubCatalogOffsetProvider() {
            return new StubCatalogOffsetProvider();
        }
    }

    /**
     * Mặc định test tự dựng PostgreSQL bằng Testcontainers. Khi CI hoặc máy dev
     * đã có sẵn một PostgreSQL dùng riêng cho test (ví dụ chạy Maven trong
     * container, nơi Testcontainers không tới được Docker daemon), đặt
     * {@code TEST_DB_URL} để trỏ thẳng vào database đó.
     */
    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");

    private static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_DB_URL == null
            ? new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("product_service")
            : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            return;
        }
        registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
        registry.add("spring.datasource.username", () -> environment("TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> environment("TEST_DB_PASSWORD", "postgres"));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    protected ProductCommandService productCommandService;
    @Autowired
    protected SkuCommandService skuCommandService;
    @Autowired
    protected MediaCommandService mediaCommandService;
    @Autowired
    protected CategoryCommandService categoryCommandService;
    @Autowired
    protected AttributeService attributeService;

    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected SkuRepository skuRepository;
    @Autowired
    protected ProductMediaRepository mediaRepository;
    @Autowired
    protected MarkdownProjectionRepository markdownRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected StubCatalogOffsetProvider offsetProvider;

    /**
     * Partition mặc định khớp layout hiện hành: {@code t.product.events} có 3
     * partition theo Product aggregate (ADR-012), {@code t.category.events} có 1.
     */
    @BeforeEach
    void resetCatalog() {
        jdbcTemplate.execute("""
                TRUNCATE product_outbox_events, product_markdown_projection, product_media,
                         product_skus, products, category_attributes, attribute_options,
                         attributes, categories RESTART IDENTITY CASCADE
                """);
        offsetProvider.reset();
        offsetProvider.withOffsets(PRODUCT_TOPIC, 100L, 200L, 300L);
        offsetProvider.withOffsets(CATEGORY_TOPIC, 42L);
    }


    // ---------------------------------------------------------------- fixtures

    protected UUID givenLeafCategory(String slug) {
        return categoryCommandService.create(
                new CategoryDtos.CreateCategoryRequest(null, slug, slug)).categoryId();
    }

    protected UUID givenChildCategory(UUID parentId, String slug) {
        return categoryCommandService.create(
                new CategoryDtos.CreateCategoryRequest(parentId, slug, slug)).categoryId();
    }

    protected UUID givenAttribute(String code) {
        return attributeService.create(
                new AttributeDtos.CreateAttributeRequest(code, code, com.platform.productservice
                        .domain.enums.AttributeValueType.TEXT)).attributeId();
    }

    protected UUID givenAttribute(String code, String name,
                                  com.platform.productservice.domain.enums.AttributeValueType type) {
        return attributeService.create(new AttributeDtos.CreateAttributeRequest(code, name, type)).attributeId();
    }

    protected void givenMapping(UUID categoryId, UUID attributeId, boolean required, boolean variant) {
        categoryCommandService.putAttribute(categoryId, attributeId,
                new CategoryDtos.PutCategoryAttributeRequest(required, variant));
    }

    /** Product DRAFT một SKU ACTIVE, không attribute nào bắt buộc. */
    protected ProductDtos.ProductResponse givenProduct(UUID categoryId, String slug, long basePriceVnd) {
        return productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, slug, slug, null, Map.of(), List.of(),
                List.of(new ProductDtos.CreateSkuRequest(
                        slug.toUpperCase() + "-1", basePriceVnd, SkuStatus.ACTIVE, Map.of(), List.of()))));
    }

    protected String etagOf(UUID productId) {
        return ProductEntity.etagOf(currentRevision(productId));
    }

    protected long currentRevision(UUID productId) {
        return productRepository.findById(productId).orElseThrow().getCatalogRevision();
    }

    // ------------------------------------------------------------------ outbox

    protected List<OutboxRow> outboxRows() {
        return jdbcTemplate.query("""
                SELECT event_type, aggregate_type, aggregate_id, partition_key, payload
                FROM product_outbox_events ORDER BY created_at, event_id
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                UUID.fromString(rs.getString("aggregate_id")),
                UUID.fromString(rs.getString("partition_key")),
                readJson(rs.getString("payload"))));
    }

    protected List<OutboxRow> outboxRowsOfType(String eventType) {
        return outboxRows().stream().filter(row -> row.eventType().equals(eventType)).toList();
    }

    private JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload is not valid JSON", e);
        }
    }

    protected record OutboxRow(String eventType,
                               String aggregateType,
                               UUID aggregateId,
                               UUID partitionKey,
                               JsonNode payload) {

        public long sequence() {
            return payload.path("sequence").asLong();
        }

        public String changeKind() {
            return payload.path("data").path("change_kind").asText();
        }

        public JsonNode data() {
            return payload.path("data");
        }
    }
}
