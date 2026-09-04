package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.domain.pricing.MarkdownPricing;
import com.platform.productservice.infrastructure.event.CatalogEventPublisher;
import com.platform.productservice.infrastructure.persistence.MarkdownProjectionRepository;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.AttributeValidator;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.ProductWriteSupport;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Command cấp SKU. Admin chỉ gửi {@code base_price_vnd}; giá công bố được tính
 * lại từ markdown projection trong cùng transaction (WF-PRODUCT-001).
 */
@Service
@RequiredArgsConstructor
public class SkuCommandService {

    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final MarkdownProjectionRepository markdownRepository;
    private final ProductWriteSupport writeSupport;
    private final AttributeValidator attributeValidator;
    private final CatalogAssembler assembler;
    private final CatalogEventPublisher eventPublisher;

    @Transactional
    public CommandResult<ProductDtos.SkuResponse> create(UUID productId,
                                                  String ifMatch,
                                                  ProductDtos.CreateSkuRequest request) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        writeSupport.lockSkus(productId);

        AttributeValidator.CategorySchema schema = attributeValidator.schemaOf(product.getCategoryId());
        attributeValidator.validateVariantAttributes(schema, request.variantAttributes());
        if (skuRepository.existsBySkuCode(request.skuCode())) {
            throw DomainException.of(ErrorCode.SKU_CODE_ALREADY_EXISTS,
                    "SKU code " + request.skuCode() + " is already used");
        }

        MarkdownProjectionEntity projection = markdownRepository.findById(productId).orElse(null);

        SkuEntity sku = new SkuEntity();
        sku.setSkuId(Uuids.next());
        sku.setProductId(productId);
        sku.setSkuCode(request.skuCode());
        sku.setBasePriceVnd(request.basePriceVnd());
        sku.setSellingPriceVnd(MarkdownPricing.sellingPrice(request.basePriceVnd(), projection));
        sku.setStatus(request.status());
        sku.setVariantAttributes(new LinkedHashMap<>(request.variantAttributes()));
        sku.setCreatedAt(now);
        sku.setUpdatedAt(now);
        writeSupport.persist(sku);
        // Media gắn SKU chỉ insert được sau khi row SKU đã tồn tại.
        writeSupport.flush();

        List<ProductMediaEntity> media =
                new ArrayList<>(mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId));
        if (request.media() != null) {
            for (ProductDtos.CreateMediaInput input : request.media()) {
                media.add(writeSupport.persist(writeSupport.newMedia(productId, sku.getSkuId(),
                        input.mediaType(), input.storageKey(), input.altText(), input.sortOrder(), now)));
            }
        }

        product.bumpRevision(now);
        writeSupport.emitSkuSnapshots(product, List.of(sku), media, SkuChangeKind.SKU_CREATED, now);
        return new CommandResult<>(assembler.toSku(sku, media), product.etag());
    }

    @Transactional
    public CommandResult<ProductDtos.SkuResponse> replace(UUID productId,
                                                   UUID skuId,
                                                   String ifMatch,
                                                   ProductDtos.ReplaceSkuRequest request) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        SkuEntity sku = requireSku(writeSupport.lockSkus(productId), productId, skuId);

        AttributeValidator.CategorySchema schema = attributeValidator.schemaOf(product.getCategoryId());
        attributeValidator.validateVariantAttributes(schema, request.variantAttributes());
        if (skuRepository.existsBySkuCodeAndSkuIdNot(request.skuCode(), skuId)) {
            throw DomainException.of(ErrorCode.SKU_CODE_ALREADY_EXISTS,
                    "SKU code " + request.skuCode() + " is already used");
        }

        MarkdownProjectionEntity projection = markdownRepository.findById(productId).orElse(null);
        sku.setSkuCode(request.skuCode());
        sku.setBasePriceVnd(request.basePriceVnd());
        sku.setSellingPriceVnd(MarkdownPricing.sellingPrice(request.basePriceVnd(), projection));
        sku.setStatus(request.status());
        sku.setVariantAttributes(new LinkedHashMap<>(request.variantAttributes()));
        sku.setUpdatedAt(now);

        product.bumpRevision(now);
        List<ProductMediaEntity> media =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId);
        writeSupport.emitSkuSnapshots(product, List.of(sku), media, SkuChangeKind.SKU_UPDATED, now);
        return new CommandResult<>(assembler.toSku(sku, media), product.etag());
    }

    /** Không xóa SKU cuối cùng của Product (PRODUCT-BR-002). */
    @Transactional
    public String delete(UUID productId, UUID skuId, String ifMatch) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);
        SkuEntity sku = requireSku(skus, productId, skuId);

        if (skus.size() <= 1) {
            throw DomainException.of(ErrorCode.SKU_LAST_DELETE_FORBIDDEN,
                    "Product " + productId + " must keep at least one SKU");
        }

        long tombstoneRevision = product.bumpRevision(now);
        eventPublisher.skuDeleted(productId, skuId, tombstoneRevision, now);
        skuRepository.delete(sku);
        return product.etag();
    }

    private SkuEntity requireSku(List<SkuEntity> skus, UUID productId, UUID skuId) {
        return skus.stream()
                .filter(sku -> sku.getSkuId().equals(skuId))
                .findFirst()
                .orElseThrow(() -> DomainException.of(ErrorCode.SKU_NOT_FOUND,
                        "SKU " + skuId + " does not belong to product " + productId));
    }
}
