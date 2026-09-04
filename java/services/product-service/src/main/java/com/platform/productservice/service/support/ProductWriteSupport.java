package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.infrastructure.event.CatalogEventPublisher;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phần dùng chung của mọi command chạm Product aggregate: lock order
 * Product then SKU, kiểm tra ETag và fan-out SKU snapshot.
 *
 * <p>Lock trong database tránh lỗi transaction đồng thời; ETag
 * {@code If-Match} tránh lost update giữa hai admin hoặc giữa admin và markdown
 * consumer (docs/product/interfaces.md).
 */
@Component
@RequiredArgsConstructor
public class ProductWriteSupport {

    private static final Pattern ETAG_PATTERN = Pattern.compile("^\"catalog-revision-([1-9][0-9]*)\"$");

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CatalogEventPublisher eventPublisher;
    private final CatalogAssembler assembler;

    @PersistenceContext
    private EntityManager entityManager;

    /** Insert row mới với ID do application sinh, không cần select trước. */
    public <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    /**
     * Đẩy các insert đang chờ xuống database.
     *
     * <p>Media gắn SKU có composite FK tới {@code product_skus}, nên row SKU phải
     * tồn tại trước khi insert media của nó.
     */
    public void flush() {
        entityManager.flush();
    }

    /** Lock Product; đây luôn là lock đầu tiên của một command. */
    public ProductEntity lockProduct(UUID productId) {
        return productRepository.lockById(productId)
                .orElseThrow(() -> DomainException.of(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product " + productId + " does not exist"));
    }

    /** Lock SKU của Product sau khi đã lock Product. */
    public List<SkuEntity> lockSkus(UUID productId) {
        return skuRepository.lockByProductId(productId);
    }

    public void requireRevision(ProductEntity product, String ifMatch) {
        long expected = parseIfMatch(ifMatch);
        if (expected != product.getCatalogRevision()) {
            throw DomainException.of(ErrorCode.PRODUCT_REVISION_MISMATCH,
                    "If-Match expects catalog_revision " + expected
                            + " but current revision is " + product.getCatalogRevision());
        }
    }

    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw DomainException.of(ErrorCode.INVALID_REQUEST, "If-Match header is required");
        }
        Matcher matcher = ETAG_PATTERN.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw DomainException.of(ErrorCode.INVALID_REQUEST,
                    "If-Match must look like \"catalog-revision-<n>\"; wildcards are not accepted");
        }
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Một snapshot đầy đủ cho mỗi SKU bị ảnh hưởng, cùng
     * {@code catalog_revision} của aggregate (ADR-010).
     *
     * @param media toàn bộ media của Product; thumbnail hiệu lực được resolve từ
     *              list đầy đủ này, còn {@code media[]} trong snapshot mới là bản
     *              đã sắp xếp và cắt bớt (ADR-013)
     */
    public void emitSkuSnapshots(ProductEntity product,
                                 List<SkuEntity> affectedSkus,
                                 List<ProductMediaEntity> media,
                                 SkuChangeKind changeKind,
                                 Instant now) {
        for (SkuEntity sku : affectedSkus) {
            eventPublisher.skuUpserted(product, sku,
                    assembler.eventMedia(sku.getSkuId(), media),
                    assembler.effectiveThumbnail(sku.getSkuId(), media),
                    changeKind, now);
        }
    }

    /** Row media mới; caller quyết định media thuộc Product hay một SKU. */
    public ProductMediaEntity newMedia(UUID productId,
                                       UUID skuId,
                                       MediaType mediaType,
                                       String storageKey,
                                       String altText,
                                       Integer sortOrder,
                                       Instant now) {
        ProductMediaEntity media = new ProductMediaEntity();
        media.setMediaId(Uuids.next());
        media.setProductId(productId);
        media.setSkuId(skuId);
        media.setMediaType(mediaType);
        media.setStorageKey(storageKey);
        media.setAltText(altText);
        media.setSortOrder(sortOrder == null ? 0 : sortOrder);
        media.setCreatedAt(now);
        media.setUpdatedAt(now);
        return media;
    }

    /**
     * Lock timeout ngắn cho background workload: timeout trở thành lỗi tạm thời
     * để consumer retry/requeue thay vì giữ transaction dài
     * (docs/product/domain.md — Concurrency).
     */
    public void applyLockTimeout(Duration timeout) {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = " + timeout.toMillis())
                .executeUpdate();
    }
}
