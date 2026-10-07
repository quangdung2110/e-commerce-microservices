package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.SkuChangeKind;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.ProductWriteSupport;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Command media. Media cấp Product ảnh hưởng mọi SKU của Product; media gắn SKU
 * chỉ ảnh hưởng chính SKU đó, nên fan-out snapshot theo đúng tập bị ảnh hưởng.
 */
@Service
@RequiredArgsConstructor
public class MediaCommandService {

    private final ProductMediaRepository mediaRepository;
    private final ProductWriteSupport writeSupport;
    private final CatalogAssembler assembler;

    @Transactional
    public CommandResult<ProductDtos.MediaResponse> create(UUID productId,
                                                                      String ifMatch,
                                                                      ProductDtos.CreateMediaRequest request) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);
        if (request.skuId() != null) {
            requireSku(skus, productId, request.skuId());
        }

        ProductMediaEntity media = writeSupport.persist(writeSupport.newMedia(productId, request.skuId(),
                request.mediaType(), request.storageKey(), request.altText(), request.sortOrder(), now));

        product.bumpRevision(now);
        List<ProductMediaEntity> allMedia =
                new ArrayList<>(mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId));
        if (allMedia.stream().noneMatch(item -> item.getMediaId().equals(media.getMediaId()))) {
            allMedia.add(media);
        }
        emitFor(product, skus, allMedia, Collections.singletonList(request.skuId()), now);
        return new CommandResult<>(assembler.toMedia(media), product.etag());
    }

    @Transactional
    public CommandResult<ProductDtos.MediaResponse> replace(UUID productId,
                                                                       UUID mediaId,
                                                                       String ifMatch,
                                                                       ProductDtos.ReplaceMediaRequest request) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);
        ProductMediaEntity media = requireMedia(productId, mediaId);
        if (request.skuId() != null) {
            requireSku(skus, productId, request.skuId());
        }

        UUID previousSkuId = media.getSkuId();
        media.setSkuId(request.skuId());
        media.setMediaType(request.mediaType());
        media.setStorageKey(request.storageKey());
        media.setAltText(request.altText());
        media.setSortOrder(request.sortOrder());
        media.setUpdatedAt(now);

        product.bumpRevision(now);
        List<ProductMediaEntity> allMedia =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId);
        emitFor(product, skus, allMedia, Arrays.asList(previousSkuId, request.skuId()), now);
        return new CommandResult<>(assembler.toMedia(media), product.etag());
    }

    @Transactional
    public String delete(UUID productId, UUID mediaId, String ifMatch) {
        Instant now = Instant.now();
        ProductEntity product = writeSupport.lockProduct(productId);
        writeSupport.requireRevision(product, ifMatch);
        List<SkuEntity> skus = writeSupport.lockSkus(productId);
        ProductMediaEntity media = requireMedia(productId, mediaId);
        UUID affectedSkuId = media.getSkuId();

        mediaRepository.delete(media);
        product.bumpRevision(now);

        List<ProductMediaEntity> remaining =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId).stream()
                        .filter(item -> !item.getMediaId().equals(mediaId))
                        .toList();
        emitFor(product, skus, remaining, Collections.singletonList(affectedSkuId), now);
        return product.etag();
    }

    /**
     * Media cấp Product (sku_id null) làm mọi SKU đổi snapshot; ngược lại chỉ
     * các SKU được nhắc tới bị ảnh hưởng.
     */
    private void emitFor(ProductEntity product,
                         List<SkuEntity> skus,
                         List<ProductMediaEntity> media,
                         List<UUID> touchedSkuIds,
                         Instant now) {
        boolean productLevel = touchedSkuIds.stream().anyMatch(Objects::isNull);
        if (productLevel) {
            writeSupport.emitSkuSnapshots(product, skus, media, SkuChangeKind.PRODUCT_UPDATED, now);
            return;
        }
        List<SkuEntity> affected = skus.stream()
                .filter(sku -> touchedSkuIds.contains(sku.getSkuId()))
                .toList();
        writeSupport.emitSkuSnapshots(product, affected, media, SkuChangeKind.SKU_UPDATED, now);
    }

    private ProductMediaEntity requireMedia(UUID productId, UUID mediaId) {
        return mediaRepository.findById(mediaId)
                .filter(media -> media.getProductId().equals(productId))
                .orElseThrow(() -> DomainException.of(ErrorCode.MEDIA_NOT_FOUND,
                        "Media " + mediaId + " does not belong to product " + productId));
    }

    private void requireSku(List<SkuEntity> skus, UUID productId, UUID skuId) {
        boolean found = skus.stream().anyMatch(sku -> sku.getSkuId().equals(skuId));
        if (!found) {
            throw DomainException.of(ErrorCode.SKU_NOT_FOUND,
                    "SKU " + skuId + " does not belong to product " + productId);
        }
    }
}
