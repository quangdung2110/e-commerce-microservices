package com.platform.productservice.integration;

import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import com.platform.productservice.domain.enums.MarkdownState;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.infrastructure.kafka.MarkdownSnapshotConsumer;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-PRODUCT-005: consumer markdown là idempotent qua {@code source_revision},
 * không cần inbox table. Test gọi thẳng listener nên không cần broker.
 */
class MarkdownConsumerIntegrationTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private MarkdownSnapshotConsumer consumer;

    @Test
    @DisplayName("Snapshot ACTIVE reprice mọi SKU và phát MARKDOWN_REPRICED cùng revision mới")
    void activeSnapshotRepricesEverySku() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);

        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, 2_000_000L, true));

        assertThat(skuRepository.findByProductIdOrderBySkuCodeAsc(product.productId()))
                .allSatisfy(sku -> assertThat(sku.getSellingPriceVnd()).isEqualTo(23_000_000L));
        assertThat(currentRevision(product.productId())).isEqualTo(2L);

        List<OutboxRow> repriced = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("MARKDOWN_REPRICED"))
                .toList();
        assertThat(repriced).hasSize(1);
        assertThat(repriced.get(0).sequence()).isEqualTo(2L);
        assertThat(repriced.get(0).data().path("sku").path("selling_price_vnd").asLong())
                .isEqualTo(23_000_000L);
        assertThat(repriced.get(0).data().path("sku").path("base_price_vnd").asLong())
                .isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Snapshot cũ hoặc trùng revision là no-op")
    void olderOrEqualRevisionIsIgnored() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);

        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, 2_000_000L, true));
        long revisionAfterFirst = currentRevision(product.productId());

        // Replay đúng snapshot đó và một snapshot cũ hơn với mức giảm khác.
        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, 2_000_000L, true));
        consumer.onMessage(percentageSnapshot(product.productId(), 3L, 5000, null, true));

        assertThat(currentRevision(product.productId())).isEqualTo(revisionAfterFirst);
        assertThat(markdownRepository.findById(product.productId()))
                .get()
                .extracting(MarkdownProjectionEntity::getSourceRevision)
                .isEqualTo(7L);
        assertThat(skuRepository.findByProductIdOrderBySkuCodeAsc(product.productId()).get(0)
                .getSellingPriceVnd()).isEqualTo(23_000_000L);
    }

    @Test
    @DisplayName("Snapshot INACTIVE trả giá về base price và phát MARKDOWN_EXPIRED")
    void inactiveSnapshotRestoresBasePrice() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);
        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, 2_000_000L, true));

        consumer.onMessage(percentageSnapshot(product.productId(), 8L, 1000, 2_000_000L, false));

        assertThat(skuRepository.findByProductIdOrderBySkuCodeAsc(product.productId()).get(0)
                .getSellingPriceVnd()).isEqualTo(25_000_000L);
        assertThat(outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("MARKDOWN_EXPIRED"))
                .toList()).hasSize(1);
    }

    @Test
    @DisplayName("Snapshot ACTIVE đến muộn sau ends_at advance revision nhưng được lưu INACTIVE")
    void lateActiveSnapshotDoesNotRevivePrice() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);

        Instant startsAt = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant endsAt = Instant.now().minus(1, ChronoUnit.DAYS);
        consumer.onMessage(snapshot(product.productId(), 9L, "ACTIVE",
                "{\"type\":\"PERCENTAGE\",\"discount_bps\":1000}", startsAt, endsAt));

        MarkdownProjectionEntity projection = markdownRepository.findById(product.productId()).orElseThrow();
        assertThat(projection.getSourceRevision()).isEqualTo(9L);
        assertThat(projection.getState()).isEqualTo(MarkdownState.INACTIVE);
        assertThat(skuRepository.findByProductIdOrderBySkuCodeAsc(product.productId()).get(0)
                .getSellingPriceVnd()).isEqualTo(25_000_000L);
        // Giá không đổi nên không có catalog event nào được phát thêm.
        assertThat(outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED)).hasSize(1);
    }

    @Test
    @DisplayName("SKU thêm sau khi markdown active được tính giá theo projection hiện hành")
    void newSkuInheritsActiveMarkdown() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);
        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, 2_000_000L, true));

        ProductDtos.SkuResponse added = skuCommandService.create(product.productId(),
                etagOf(product.productId()),
                new ProductDtos.CreateSkuRequest("EXTRA-1", 10_000_000L,
                        com.platform.productservice.domain.enums.SkuStatus.ACTIVE,
                        java.util.Map.of(), List.of())).body();

        assertThat(added.sellingPriceVnd()).isEqualTo(9_000_000L);
    }

    @Test
    @DisplayName("Đổi base price khi markdown active được reprice trong cùng transaction")
    void basePriceChangeIsRepriced() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);
        consumer.onMessage(percentageSnapshot(product.productId(), 7L, 1000, null, true));
        UUID skuId = product.skus().get(0).skuId();

        ProductDtos.SkuResponse replaced = skuCommandService.replace(product.productId(), skuId,
                etagOf(product.productId()),
                new ProductDtos.ReplaceSkuRequest("PIXEL-1", 30_000_000L,
                        com.platform.productservice.domain.enums.SkuStatus.ACTIVE,
                        java.util.Map.of())).body();

        assertThat(replaced.sellingPriceVnd()).isEqualTo(27_000_000L);
    }

    @Test
    @DisplayName("Snapshot của Product đã bị xóa là no-op, không làm hỏng consumer")
    void snapshotForDeletedProductIsIgnored() {
        UUID missingProductId = UUID.randomUUID();

        consumer.onMessage(percentageSnapshot(missingProductId, 1L, 1000, null, true));

        assertThat(markdownRepository.findById(missingProductId)).isEmpty();
    }

    @Test
    @DisplayName("Event khác type trên topic markdown bị bỏ qua")
    void unknownEventTypeIsSkipped() {
        String event = """
                {"specversion":"1.0","id":"x","type":"com.ecommerce.promotion.voucher-created.v1",
                 "source":"/services/promotion","subject":"vouchers/1","time":"2026-09-03T10:00:00Z",
                 "datacontenttype":"application/json","dataschema":"x","data":{}}
                """;

        consumer.onMessage(event);

        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("Event sai hợp đồng ném lỗi non-retryable để error handler đẩy DLQ")
    void malformedEventIsNonRetryable() {
        String broken = """
                {"specversion":"1.0","id":"x","type":"com.ecommerce.promotion.product-markdown-snapshot.v1",
                 "source":"/services/promotion","subject":"products/x","time":"2026-09-03T10:00:00Z",
                 "datacontenttype":"application/json","dataschema":"x",
                 "data":{"product_id":"not-a-uuid"}}
                """;

        assertThatThrownBy(() -> consumer.onMessage(broken))
                .isInstanceOf(MarkdownSnapshotConsumer.NonRetryableEventException.class);
        assertThatThrownBy(() -> consumer.onMessage("{not json"))
                .isInstanceOf(MarkdownSnapshotConsumer.NonRetryableEventException.class);
    }

    private String percentageSnapshot(UUID productId, long sourceRevision, int bps, Long cap, boolean active) {
        String discount = cap == null
                ? "{\"type\":\"PERCENTAGE\",\"discount_bps\":" + bps + "}"
                : "{\"type\":\"PERCENTAGE\",\"discount_bps\":" + bps + ",\"max_discount_vnd\":" + cap + "}";
        return snapshot(productId, sourceRevision, active ? "ACTIVE" : "INACTIVE", discount,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private String snapshot(UUID productId,
                            long sourceRevision,
                            String state,
                            String discountJson,
                            Instant startsAt,
                            Instant endsAt) {
        return """
                {
                  "specversion": "1.0",
                  "id": "%s",
                  "type": "com.ecommerce.promotion.product-markdown-snapshot.v1",
                  "source": "/services/promotion",
                  "subject": "products/%s",
                  "time": "%s",
                  "datacontenttype": "application/json",
                  "dataschema": "https://contracts.ecommerce.local/events/product/v1/schemas/product-markdown-snapshot.schema.json",
                  "sequence": %d,
                  "data": {
                    "product_id": "%s",
                    "promotion_rule_id": 4242,
                    "source_revision": %d,
                    "state": "%s",
                    "discount": %s,
                    "starts_at": "%s",
                    "ends_at": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), productId, Instant.now(), sourceRevision,
                productId, sourceRevision, state, discountJson, startsAt, endsAt);
    }
}
