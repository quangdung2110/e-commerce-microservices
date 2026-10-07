package com.platform.productservice.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.AggregateType;
import com.platform.productservice.domain.enums.CategoryChangeKind;
import com.platform.productservice.domain.enums.SkuChangeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dựng CloudEvents structured JSON của catalog và ghi vào outbox.
 *
 * <p>Mọi event Product/SKU dùng {@code aggregate_type = Product},
 * {@code aggregate_id = partition_key = product_id} và
 * {@code sequence = catalog_revision}; event Category dùng {@code category_id}
 * và {@code category_revision} (ADR-010, ADR-012).
 *
 * <p>Payload phải khớp contracts/events/product/v1/schemas/*.schema.json —
 * các schema đó đặt {@code additionalProperties: false}, nên không được thêm
 * field ngoài hợp đồng.
 */
@Component
@RequiredArgsConstructor
public class CatalogEventPublisher {

    private final ObjectMapper objectMapper;
    private final OutboxWriter outboxWriter;

    /**
     * Snapshot đầy đủ của một SKU bị ảnh hưởng, kèm Product context.
     *
     * @param media media của SKU cộng media cấp Product, đã sắp xếp sẵn — schema
     *              catalog-common chỉ có chỗ cho media ở cấp SKU
     * @param thumbnailStorageKey thumbnail hiệu lực đã resolve, {@code null} khi SKU
     *                            không có ảnh nào hợp lệ (ADR-013); publisher không
     *                            tự suy ra từ {@code media} vì list đó đã bị cắt
     */
    public void skuUpserted(ProductEntity product,
                            SkuEntity sku,
                            List<ProductMediaEntity> media,
                            String thumbnailStorageKey,
                            SkuChangeKind changeKind,
                            Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.set("product", productNode(product));
        data.set("sku", skuNode(sku, media, thumbnailStorageKey));
        data.put("change_kind", changeKind.name());

        emit(CatalogEventTypes.SKU_UPSERTED,
                "skus/" + sku.getSkuId(),
                product.getCatalogRevision(),
                data,
                AggregateType.Product,
                product.getProductId(),
                product.getProductId(),
                now);
    }

    /** Tombstone một SKU; revision phải là revision kế tiếp của Product. */
    public void skuDeleted(UUID productId, UUID skuId, long catalogRevision, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("product_id", productId.toString());
        data.put("sku_id", skuId.toString());
        data.put("catalog_revision", catalogRevision);
        data.put("deleted_at", isoInstant(now));

        emit(CatalogEventTypes.SKU_DELETED,
                "skus/" + skuId,
                catalogRevision,
                data,
                AggregateType.Product,
                productId,
                productId,
                now);
    }

    /** Tombstone Product kèm toàn bộ SKU đã bị xóa theo cascade. */
    public void productDeleted(UUID productId, List<UUID> skuIds, long catalogRevision, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("product_id", productId.toString());
        ArrayNode ids = data.putArray("sku_ids");
        skuIds.forEach(id -> ids.add(id.toString()));
        data.put("catalog_revision", catalogRevision);
        data.put("deleted_at", isoInstant(now));

        emit(CatalogEventTypes.PRODUCT_DELETED,
                "products/" + productId,
                catalogRevision,
                data,
                AggregateType.Product,
                productId,
                productId,
                now);
    }

    /**
     * Snapshot Category kèm path dẫn xuất tại thời điểm commit.
     *
     * @param path từ root tới chính category, mỗi phần tử là một segment
     */
    public void categoryUpserted(CategoryEntity category,
                                 List<CategoryEntity> path,
                                 boolean leaf,
                                 CategoryChangeKind changeKind,
                                 Instant now) {
        ObjectNode categoryNode = objectMapper.createObjectNode();
        categoryNode.put("category_id", category.getCategoryId().toString());
        if (category.getParentId() == null) {
            categoryNode.putNull("parent_id");
        } else {
            categoryNode.put("parent_id", category.getParentId().toString());
        }
        categoryNode.put("name", category.getName());
        categoryNode.put("slug", category.getSlug());
        categoryNode.put("category_revision", category.getCategoryRevision());
        ArrayNode pathNode = categoryNode.putArray("path");
        for (CategoryEntity segment : path) {
            ObjectNode node = pathNode.addObject();
            node.put("category_id", segment.getCategoryId().toString());
            node.put("name", segment.getName());
            node.put("slug", segment.getSlug());
        }
        categoryNode.put("is_leaf", leaf);
        categoryNode.put("updated_at", isoInstant(category.getUpdatedAt()));

        ObjectNode data = objectMapper.createObjectNode();
        data.set("category", categoryNode);
        data.put("change_kind", changeKind.name());

        emit(CatalogEventTypes.CATEGORY_UPSERTED,
                "categories/" + category.getCategoryId(),
                category.getCategoryRevision(),
                data,
                AggregateType.Category,
                category.getCategoryId(),
                category.getCategoryId(),
                now);
    }

    public void categoryDeleted(UUID categoryId, long categoryRevision, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("category_id", categoryId.toString());
        data.put("category_revision", categoryRevision);
        data.put("deleted_at", isoInstant(now));

        emit(CatalogEventTypes.CATEGORY_DELETED,
                "categories/" + categoryId,
                categoryRevision,
                data,
                AggregateType.Category,
                categoryId,
                categoryId,
                now);
    }

    /**
     * Control event yêu cầu Search coalesce rồi full reindex sau khi cây
     * category đổi (rename, đổi slug hoặc đổi parent).
     */
    public void catalogReindexRequested(UUID rootCategoryId,
                                        UUID changedCategoryId,
                                        long categoryRevision,
                                        Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("reason", "CATEGORY_TREE_CHANGED");
        data.put("root_category_id", rootCategoryId.toString());
        data.put("category_revision", categoryRevision);
        data.put("requested_at", isoInstant(now));

        emit(CatalogEventTypes.CATALOG_REINDEX_REQUESTED,
                "categories/" + changedCategoryId,
                categoryRevision,
                data,
                AggregateType.Category,
                changedCategoryId,
                rootCategoryId,
                now);
    }

    private ObjectNode productNode(ProductEntity product) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("product_id", product.getProductId().toString());
        node.put("category_id", product.getCategoryId().toString());
        node.put("name", product.getName());
        node.put("slug", product.getSlug());
        if (product.getDescription() == null) {
            node.putNull("description");
        } else {
            node.put("description", product.getDescription());
        }
        node.set("attributes", attributesNode(product.getAttributes()));
        node.put("status", product.getStatus().name());
        node.put("catalog_revision", product.getCatalogRevision());
        node.put("updated_at", isoInstant(product.getUpdatedAt()));
        return node;
    }

    private ObjectNode skuNode(SkuEntity sku, List<ProductMediaEntity> media, String thumbnailStorageKey) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sku_id", sku.getSkuId().toString());
        node.put("sku_code", sku.getSkuCode());
        node.put("base_price_vnd", sku.getBasePriceVnd());
        node.put("selling_price_vnd", sku.getSellingPriceVnd());
        node.put("status", sku.getStatus().name());
        node.set("variant_attributes", attributesNode(sku.getVariantAttributes()));
        ArrayNode mediaNode = node.putArray("media");
        for (ProductMediaEntity item : media) {
            ObjectNode entry = mediaNode.addObject();
            entry.put("media_id", item.getMediaId().toString());
            entry.put("media_type", item.getMediaType().name());
            entry.put("storage_key", item.getStorageKey());
            if (item.getAltText() == null) {
                entry.putNull("alt_text");
            } else {
                entry.put("alt_text", item.getAltText());
            }
            entry.put("sort_order", item.getSortOrder());
        }
        if (thumbnailStorageKey == null) {
            node.putNull("thumbnail_storage_key");
        } else {
            node.put("thumbnail_storage_key", thumbnailStorageKey);
        }
        node.put("updated_at", isoInstant(sku.getUpdatedAt()));
        return node;
    }

    private ObjectNode attributesNode(Map<String, Object> attributes) {
        ObjectNode node = objectMapper.createObjectNode();
        if (attributes == null) {
            return node;
        }
        attributes.forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
        return node;
    }

    private void emit(String eventType,
                      String subject,
                      long sequence,
                      ObjectNode data,
                      AggregateType aggregateType,
                      UUID aggregateId,
                      UUID partitionKey,
                      Instant now) {
        UUID eventId = Uuids.next();

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("specversion", CatalogEventTypes.SPEC_VERSION);
        envelope.put("id", eventId.toString());
        envelope.put("type", eventType);
        envelope.put("source", CatalogEventTypes.SOURCE);
        envelope.put("subject", subject);
        envelope.put("time", isoInstant(now));
        envelope.put("datacontenttype", CatalogEventTypes.DATA_CONTENT_TYPE);
        envelope.put("dataschema", CatalogEventTypes.dataSchema(eventType));
        envelope.put("sequence", sequence);
        String correlationId = EventContext.correlationId();
        if (correlationId != null) {
            envelope.put("correlationid", correlationId);
        }
        String traceparent = EventContext.traceparent();
        if (traceparent != null) {
            envelope.put("traceparent", traceparent);
        }
        envelope.set("data", data);

        outboxWriter.append(eventId, eventType, aggregateType, aggregateId, partitionKey,
                envelope.toString(), now);
    }

    private static String isoInstant(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
