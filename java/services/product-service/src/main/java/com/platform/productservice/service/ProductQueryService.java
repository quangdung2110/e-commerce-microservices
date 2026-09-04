package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.CursorCodec;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read path quản trị. Đây không phải API browse/search cho shopper: Search sở
 * hữu năng lực đó và dựng read model từ catalog event.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final CatalogAssembler assembler;
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
}
