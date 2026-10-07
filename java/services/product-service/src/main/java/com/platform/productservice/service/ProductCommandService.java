package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.ProductLifecycle;
import com.platform.productservice.domain.ProductSlug;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.infrastructure.event.CatalogEventPublisher;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.AttributeValidator;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.CategoryTreeService;
import com.platform.productservice.service.support.ProductWriteSupport;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Command cấp Product: tạo, thay phần Product-level, đổi lifecycle và hard delete
 * (WF-PRODUCT-001, WF-PRODUCT-002).
 *
 * <p>Mỗi command: validate → lock Product → lock SKU → mutate → tăng
 * {@code catalog_revision} đúng một lần → insert outbox → commit. Không gọi
 * Inventory và không ghi cache trong bất kỳ luồng nào.
 */
@Service
@RequiredArgsConstructor
public class ProductCommandService {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final ProductWriteSupport writeSupport;
    private final CategoryTreeService categoryTree;
    private final AttributeValidator attributeValidator;
    private final CatalogAssembler assembler;
    private final CatalogEventPublisher eventPublisher;

    /**
     * Tạo Product {@code DRAFT} cùng ít nhất một SKU trong một transaction
     * (PRODUCT-BR-002). Operation này không idempotent: schema không có
     * idempotency store, caller timeout phải tra cứu theo slug trước khi retry.
     */
    @Transactional
    public ProductDtos.ProductResponse create(ProductDtos.CreateProductRequest request) {
        Instant now = Instant.now();
        ProductSlug.require(request.slug());
        CategoryEntity category = categoryTree.requireLeaf(request.categoryId());
        AttributeValidator.CategorySchema schema = attributeValidator.schemaOf(category.getCategoryId());
        attributeValidator.validateProductAttributes(schema, request.attributes());

        if (productRepository.existsBySlug(request.slug())) {
            throw DomainException.of(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Product slug " + request.slug() + " is already used");
        }

        ProductEntity product = new ProductEntity();
        product.setProductId(Uuids.next());
        product.setCategoryId(category.getCategoryId());
        product.setName(request.name());
        product.setSlug(request.slug());
        product.setDescription(request.description());
        product.setAttributes(new LinkedHashMap<>(request.attributes()));
        product.setStatus(ProductStatus.DRAFT);
        product.setCatalogRevision(1L);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        writeSupport.persist(product);

        // SKU được insert trước media: composite FK của product_media tham chiếu
        // (product_id, sku_id) nên row SKU phải tồn tại trước.
        List<ProductMediaEntity> media = new ArrayList<>();
        Set<String> skuCodes = new HashSet<>();
        List<SkuEntity> skus = new ArrayList<>();
        for (ProductDtos.CreateSkuRequest skuRequest : request.skus()) {
            if (!skuCodes.add(skuRequest.skuCode())) {
                throw DomainException.of(ErrorCode.SKU_CODE_ALREADY_EXISTS,
                        "SKU code " + skuRequest.skuCode() + " is duplicated in the request");
            }
            attributeValidator.validateVariantAttributes(schema, skuRequest.variantAttributes());
            if (skuRepository.existsBySkuCode(skuRequest.skuCode())) {
                throw DomainException.of(ErrorCode.SKU_CODE_ALREADY_EXISTS,
                        "SKU code " + skuRequest.skuCode() + " is already used");
            }

            SkuEntity sku = new SkuEntity();
            sku.setSkuId(Uuids.next());
            sku.setProductId(product.getProductId());
            sku.setSkuCode(skuRequest.skuCode());
            sku.setBasePriceVnd(skuRequest.basePriceVnd());
            // Product mới chưa có markdown projection nên giá công bố bằng base price.
            sku.setSellingPriceVnd(skuRequest.basePriceVnd());
            sku.setStatus(skuRequest.status());
            sku.setVariantAttributes(new LinkedHashMap<>(skuRequest.variantAttributes()));
            sku.setCreatedAt(now);
            sku.setUpdatedAt(now);
            writeSupport.persist(sku);
            skus.add(sku);
        }
        writeSupport.flush();

        if (request.media() != null) {
            for (ProductDtos.CreateMediaInput input : request.media()) {
                media.add(writeSupport.persist(newMedia(product.getProductId(), null, input, now)));
            }
        }
        for (int index = 0; index < request.skus().size(); index++) {
            List<ProductDtos.CreateMediaInput> skuMedia = request.skus().get(index).media();
            if (skuMedia == null) {
                continue;
            }
            UUID skuId = skus.get(index).getSkuId();
            for (ProductDtos.CreateMediaInput input : skuMedia) {
                media.add(writeSupport.persist(newMedia(product.getProductId(), skuId, input, now)));
            }
        }

        writeSupport.emitSkuSnapshots(product, skus, media, SkuChangeKind.PRODUCT_CREATED, now);
        return assembler.toProduct(product, skus, media);
    }

    /** Thay phần Product-level; SKU và media có resource riêng. */
    @Transactional
    public ProductDtos.ProductResponse replace(UUID productId,
                                               String ifMatch,
                                               ProductDtos.ReplaceProductRequest request) {
        Instant now = Instant.now();
        ProductSlug.require(request.slug());
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);

        CategoryEntity category = categoryTree.requireLeaf(request.categoryId());
        AttributeValidator.CategorySchema schema = attributeValidator.schemaOf(category.getCategoryId());
        attributeValidator.validateProductAttributes(schema, request.attributes());
        if (!category.getCategoryId().equals(product.getCategoryId())) {
            // Category mới có bộ mapping khác, mọi variant attribute hiện hữu phải còn hợp lệ.
            skus.forEach(sku -> attributeValidator.validateVariantAttributes(schema, sku.getVariantAttributes()));
        }
        if (productRepository.existsBySlugAndProductIdNot(request.slug(), productId)) {
            throw DomainException.of(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Product slug " + request.slug() + " is already used");
        }

        product.setCategoryId(category.getCategoryId());
        product.setName(request.name());
        product.setSlug(request.slug());
        product.setDescription(request.description());
        product.setAttributes(new LinkedHashMap<>(request.attributes()));
        product.bumpRevision(now);

        List<ProductMediaEntity> media =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId);
        writeSupport.emitSkuSnapshots(product, skus, media, SkuChangeKind.PRODUCT_UPDATED, now);
        return assembler.toProduct(product, skus, media);
    }

    /**
     * Activate, hide hoặc show. {@code ACTIVE} yêu cầu tối thiểu một SKU
     * {@code ACTIVE} (PRODUCT-BR-008); hide/show không đọc Inventory
     * (PRODUCT-BR-009).
     */
    @Transactional
    public ProductDtos.ProductResponse changeStatus(UUID productId, String ifMatch, ProductStatus target) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);

        ProductLifecycle.assertTransitionAllowed(product.getStatus(), target);
        if (target == ProductStatus.ACTIVE
                && skus.stream().noneMatch(sku -> sku.getStatus() == SkuStatus.ACTIVE)) {
            throw DomainException.of(ErrorCode.PRODUCT_NOT_SUBMITTABLE,
                    "Product " + productId + " has no ACTIVE SKU");
        }

        product.setStatus(target);
        product.bumpRevision(now);

        List<ProductMediaEntity> media =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId);
        writeSupport.emitSkuSnapshots(product, skus, media, SkuChangeKind.PRODUCT_STATUS_CHANGED, now);
        return assembler.toProduct(product, skus, media);
    }

    /**
     * Hard delete. Transaction chụp {@code sku_ids}, ghi tombstone với revision
     * kế tiếp rồi mới xóa row; FK cascade dọn SKU, media và markdown projection
     * (PRODUCT-BR-010).
     */
    @Transactional
    public void delete(UUID productId, String ifMatch) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<UUID> skuIds = writeSupport.lockSkus(productId).stream()
                .map(SkuEntity::getSkuId)
                .toList();

        long tombstoneRevision = product.getCatalogRevision() + 1;
        eventPublisher.productDeleted(productId, skuIds, tombstoneRevision, now);
        productRepository.delete(product);
    }

    private ProductMediaEntity newMedia(UUID productId,
                                        UUID skuId,
                                        ProductDtos.CreateMediaInput input,
                                        Instant now) {
        return writeSupport.newMedia(productId, skuId, input.mediaType(), input.storageKey(),
                input.altText(), input.sortOrder(), now);
    }
}
