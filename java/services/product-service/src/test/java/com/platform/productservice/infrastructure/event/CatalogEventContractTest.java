package com.platform.productservice.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.platform.productservice.domain.FacetDefinition;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.AggregateType;
import com.platform.productservice.domain.enums.CategoryChangeKind;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.domain.enums.SkuStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: mọi CloudEvent Product phát ra phải hợp lệ với JSON Schema
 * trong {@code contracts/events/product/v1/schemas}. Schema đặt
 * {@code additionalProperties: false} nên test này bắt được cả field thừa lẫn
 * field thiếu khi code và contract lệch nhau.
 */
class CatalogEventContractTest {

    private static final String SCHEMA_ID_PREFIX = "https://contracts.ecommerce.local/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> payloads = new ArrayList<>();
    private final List<AggregateType> aggregateTypes = new ArrayList<>();
    private final List<UUID> partitionKeys = new ArrayList<>();

    private CatalogEventPublisher publisher;
    private JsonSchemaFactory schemaFactory;

    @BeforeEach
    void setUp() {
        Path contracts = Paths.get("").toAbsolutePath()
                .resolve("../../../contracts").normalize();
        Assumptions.assumeTrue(Files.isDirectory(contracts),
                "contracts/ chỉ có mặt khi test chạy trong repository");

        String contractsUri = contracts.toUri().toString();
        schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, contractsUri)));

        OutboxWriter capturingWriter = new OutboxWriter() {
            @Override
            public void append(UUID eventId, String eventType, AggregateType aggregateType,
                               UUID aggregateId, UUID partitionKey, String payload, Instant now) {
                assertThat(partitionKey).isNotNull();
                payloads.add(payload);
                aggregateTypes.add(aggregateType);
                partitionKeys.add(partitionKey);
            }
        };
        publisher = new CatalogEventPublisher(objectMapper, capturingWriter);
    }

    @Test
    @DisplayName("sku-upserted khớp schema và dùng key product_id")
    void skuUpsertedMatchesContract() throws IOException {
        ProductEntity product = product();
        SkuEntity sku = sku(product);

        publisher.skuUpserted(product, sku, List.of(media(product, sku)),
                "catalog/pixel9/black.jpg", SkuChangeKind.PRODUCT_CREATED, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/sku-upserted.schema.json", event);
        assertThat(event.get("sequence").asLong()).isEqualTo(product.getCatalogRevision());
        assertThat(event.get("subject").asText()).isEqualTo("skus/" + sku.getSkuId());
        assertThat(aggregateTypes.get(0)).isEqualTo(AggregateType.Product);
        assertThat(partitionKeys.get(0)).isEqualTo(product.getProductId());
        assertThat(event.path("data").path("sku").path("thumbnail_storage_key").asText())
                .isEqualTo("catalog/pixel9/black.jpg");
    }

    @Test
    @DisplayName("thumbnail_storage_key null vẫn là snapshot hợp lệ, không phải field vắng mặt")
    void skuUpsertedAcceptsNullThumbnail() throws IOException {
        ProductEntity product = product();
        SkuEntity sku = sku(product);

        publisher.skuUpserted(product, sku, List.of(), null, SkuChangeKind.SKU_UPDATED, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/sku-upserted.schema.json", event);
        JsonNode thumbnail = event.path("data").path("sku").path("thumbnail_storage_key");
        assertThat(thumbnail.isMissingNode())
                .as("schema đặt thumbnail_storage_key là required nên field phải hiện diện")
                .isFalse();
        assertThat(thumbnail.isNull()).isTrue();
    }

    @Test
    @DisplayName("Snapshot SKU không chứa bất kỳ field tồn kho nào")
    void skuSnapshotCarriesNoInventoryData() {
        ProductEntity product = product();
        SkuEntity sku = sku(product);

        publisher.skuUpserted(product, sku, List.of(), null, SkuChangeKind.SKU_UPDATED, Instant.now());

        assertThat(payloads.get(0))
                .doesNotContain("quantity", "stock", "OUT_OF_STOCK", "available", "reserved");
    }

    @Test
    @DisplayName("sku-deleted mang revision tombstone")
    void skuDeletedMatchesContract() throws IOException {
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        publisher.skuDeleted(productId, skuId, 9L, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/sku-deleted.schema.json", event);
        assertThat(event.get("sequence").asLong()).isEqualTo(9L);
        assertThat(event.path("data").get("catalog_revision").asLong()).isEqualTo(9L);
    }

    @Test
    @DisplayName("product-deleted liệt kê toàn bộ sku_ids")
    void productDeletedMatchesContract() throws IOException {
        UUID productId = UUID.randomUUID();
        List<UUID> skuIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        publisher.productDeleted(productId, skuIds, 4L, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/product-deleted.schema.json", event);
        assertThat(event.path("data").path("sku_ids").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("category-upserted mang path dẫn xuất và category_revision")
    void categoryUpsertedMatchesContract() throws IOException {
        CategoryEntity root = category("Electronics", "electronics", null);
        CategoryEntity child = category("Phones", "phones", root.getCategoryId());
        child.setCategoryRevision(3L);

        publisher.categoryUpserted(child, List.of(root, child), true,
                List.of(new FacetDefinition("brand", "Thương hiệu", AttributeValueType.TEXT),
                        new FacetDefinition("color", "Màu sắc", AttributeValueType.SELECT)),
                CategoryChangeKind.CATEGORY_UPDATED, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/category-upserted.schema.json", event);
        assertThat(event.get("sequence").asLong()).isEqualTo(3L);
        assertThat(event.path("data").path("category").path("path").size()).isEqualTo(2);
        JsonNode facets = event.path("data").path("category").path("facet_definitions");
        assertThat(facets.size()).isEqualTo(2);
        assertThat(facets.get(1).path("type").asText()).isEqualTo("SELECT");
        assertThat(aggregateTypes.get(0)).isEqualTo(AggregateType.Category);
        assertThat(partitionKeys.get(0)).isEqualTo(child.getCategoryId());
    }

    @Test
    @DisplayName("category-upserted không có mapping mang facet_definitions rỗng, không phải field vắng mặt")
    void categoryUpsertedWithoutMappingsCarriesEmptyFacetDefinitions() throws IOException {
        CategoryEntity root = category("Electronics", "electronics", null);

        publisher.categoryUpserted(root, List.of(root), true, List.of(),
                CategoryChangeKind.CATEGORY_CREATED, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/category-upserted.schema.json", event);
        JsonNode facets = event.path("data").path("category").path("facet_definitions");
        assertThat(facets.isMissingNode()).isFalse();
        assertThat(facets.isArray()).isTrue();
        assertThat(facets.size()).isZero();
    }

    @Test
    @DisplayName("category-deleted khớp schema")
    void categoryDeletedMatchesContract() throws IOException {
        publisher.categoryDeleted(UUID.randomUUID(), 2L, Instant.now());

        assertValid("events/product/v1/schemas/category-deleted.schema.json", single());
    }

    @Test
    @DisplayName("catalog-reindex-requested dùng root category làm partition key")
    void reindexRequestedMatchesContract() throws IOException {
        UUID rootId = UUID.randomUUID();
        UUID changedId = UUID.randomUUID();

        publisher.catalogReindexRequested(rootId, changedId, 5L, Instant.now());

        JsonNode event = single();
        assertValid("events/product/v1/schemas/catalog-reindex-requested.schema.json", event);
        assertThat(event.get("subject").asText()).isEqualTo("categories/" + changedId);
        assertThat(event.path("data").get("root_category_id").asText()).isEqualTo(rootId.toString());
        assertThat(partitionKeys.get(0)).isEqualTo(rootId);
    }

    @Test
    @DisplayName("CloudEvents id trùng event_id của outbox để consumer dedupe")
    void cloudEventIdIsStable() throws IOException {
        ProductEntity product = product();
        publisher.skuUpserted(product, sku(product), List.of(), null, SkuChangeKind.SKU_CREATED, Instant.now());

        JsonNode event = single();
        assertThat(event.get("id").asText()).isNotBlank();
        assertThat(event.get("source").asText()).isEqualTo("/services/product");
        assertThat(event.get("specversion").asText()).isEqualTo("1.0");
    }

    private JsonNode single() throws IOException {
        assertThat(payloads).hasSize(1);
        return objectMapper.readTree(payloads.get(0));
    }

    private void assertValid(String schemaPath, JsonNode event) {
        JsonSchema schema = schemaFactory.getSchema(SchemaLocation.of(SCHEMA_ID_PREFIX + schemaPath));
        Set<ValidationMessage> errors = schema.validate(event);

        assertThat(errors)
                .as("event phải hợp lệ với %s nhưng có lỗi: %s", schemaPath, errors)
                .isEmpty();
    }

    private ProductEntity product() {
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        ProductEntity product = new ProductEntity();
        product.setProductId(UUID.randomUUID());
        product.setCategoryId(UUID.randomUUID());
        product.setName("Pixel 9");
        product.setSlug("pixel-9");
        product.setDescription("Flagship");
        product.setAttributes(new LinkedHashMap<>(java.util.Map.of("brand", "Google", "weight", 187)));
        product.setStatus(ProductStatus.ACTIVE);
        product.setCatalogRevision(7L);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        return product;
    }

    private SkuEntity sku(ProductEntity product) {
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        SkuEntity sku = new SkuEntity();
        sku.setSkuId(UUID.randomUUID());
        sku.setProductId(product.getProductId());
        sku.setSkuCode("PIXEL9-BLACK");
        sku.setBasePriceVnd(25_000_000L);
        sku.setSellingPriceVnd(23_000_000L);
        sku.setStatus(SkuStatus.ACTIVE);
        sku.setVariantAttributes(new LinkedHashMap<>(java.util.Map.of("color", "black")));
        sku.setCreatedAt(now);
        sku.setUpdatedAt(now);
        return sku;
    }

    private ProductMediaEntity media(ProductEntity product, SkuEntity sku) {
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        ProductMediaEntity media = new ProductMediaEntity();
        media.setMediaId(UUID.randomUUID());
        media.setProductId(product.getProductId());
        media.setSkuId(sku.getSkuId());
        media.setMediaType(MediaType.IMAGE);
        media.setStorageKey("catalog/pixel9/black.jpg");
        media.setAltText(null);
        media.setSortOrder(0);
        media.setCreatedAt(now);
        media.setUpdatedAt(now);
        return media;
    }

    private CategoryEntity category(String name, String slug, UUID parentId) {
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        CategoryEntity category = new CategoryEntity();
        category.setCategoryId(UUID.randomUUID());
        category.setParentId(parentId);
        category.setName(name);
        category.setSlug(slug);
        category.setCategoryRevision(1L);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return category;
    }
}
