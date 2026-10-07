package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.BuyerDetailAssembler;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.CursorCodec;
import com.platform.productservice.web.dto.BuyerDtos;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read path quản trị. Đây không phải API browse/search cho shopper: Search sở
 * hữu năng lực đó và dựng read model từ catalog event.
 *
 * <p>Ngoại lệ duy nhất là {@link #getBuyerDetail(String)}, point-read catalog
 * cho Product Detail BFF; nó vẫn không phải browse API và không trả tồn kho.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final CatalogAssembler assembler;
    private final BuyerDetailAssembler buyerAssembler;
    private final CursorCodec cursorCodec;

    public ProductDtos.ProductPageResponse list(UUID categoryId,
                                                ProductStatus status,
                                                String cursor,
                                                int limit) {
        UUID after = cursor == null ? null : cursorCodec.decodeId(cursor);
        List<ProductEntity> products = productRepository.findPage(
                categoryId == null ? null : categoryId.toString(),
                status == null ? null : status.name(),
                after == null ? null : after.toString(),
                limit);

        String nextCursor = products.size() < limit
                ? null
                : cursorCodec.encodeId(products.get(products.size() - 1).getProductId());
        return new ProductDtos.ProductPageResponse(
                products.stream().map(assembler::toProductSummary).toList(), nextCursor);
    }

    public ProductDtos.ProductResponse get(UUID productId) {
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> DomainException.of(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product " + productId + " does not exist"));
        List<SkuEntity> skus = skuRepository.findByProductIdOrderBySkuCodeAsc(productId);
        List<ProductMediaEntity> media =
                mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(productId);
        return assembler.toProduct(product, skus, media);
    }

    /**
     * Buyer-read theo slug cho Product Detail BFF (ADR-015, ADR-018, WF-PRODUCT-007).
     *
     * <p>Chỉ Product {@code ACTIVE} được trả, SKU {@code INACTIVE} bị lọc cùng media
     * của chúng, và Product không còn SKU saleable trả {@code 404}. Slug không tồn tại,
     * {@code DRAFT} và {@code HIDDEN} dùng chung một lỗi để không rò rỉ sự tồn tại của
     * catalog chưa công bố.
     *
     * <p>Response là projection buyer đã resolve category path, nhãn thuộc tính, trục
     * biến thể và gallery giới hạn. Nó không có field tồn kho nào —
     * {@link BuyerDtos.BuyerProductDetail} không định nghĩa chúng — và method này không
     * gọi Inventory hay Search. Availability theo SKU là read composition của BFF.
     */
    public BuyerDtos.BuyerProductDetail getBuyerDetail(String slug) {
        ProductEntity product = productRepository.findBySlug(slug)
                .filter(candidate -> candidate.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> DomainException.of(ErrorCode.PRODUCT_NOT_FOUND,
                        "No catalog-active product is published at slug " + slug));

        List<SkuEntity> saleableSkus =
                skuRepository.findByProductIdOrderBySkuCodeAsc(product.getProductId()).stream()
                        .filter(sku -> sku.getStatus() == SkuStatus.ACTIVE)
                        .toList();
        if (saleableSkus.isEmpty()) {
            throw DomainException.of(ErrorCode.PRODUCT_NOT_FOUND,
                    "No catalog-active product is published at slug " + slug);
        }

        UUID productId = product.getProductId();
        return buyerAssembler.toBuyerDetail(
                product,
                saleableSkus,
                mediaRepository.findBuyerGallery(productId,
                        BuyerDetailAssembler.PRODUCT_MEDIA_LIMIT, BuyerDetailAssembler.SKU_MEDIA_LIMIT),
                mediaRepository.findThumbnailCandidates(productId));
    }
}
