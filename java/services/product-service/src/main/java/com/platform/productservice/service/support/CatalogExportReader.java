package com.platform.productservice.service.support;

import com.platform.productservice.domain.FacetDefinition;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.infrastructure.persistence.CategoryRepository;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phần đọc PostgreSQL của export, tách khỏi
 * {@link com.platform.productservice.service.CatalogExportService} để việc chụp
 * replay offsets từ Kafka nằm <b>ngoài</b> transaction.
 *
 * <p>Nếu gộp chung, một lần gọi Kafka sẽ giữ connection Hikari suốt thời gian chờ
 * broker, và thứ tự "chụp offset trước khi scan" của ADR-014 sẽ bị che sau ranh
 * giới transaction.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogExportReader {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryTreeService categoryTree;
    private final FacetDefinitionResolver facetResolver;
    private final CatalogAssembler assembler;

    /** Một trang Product theo keyset {@code (lastId, bound]}, kèm SKU và media. */
    public List<ProductDtos.ProductResponse> products(UUID lastId, UUID bound, int limit) {
        List<ProductEntity> products = productRepository.findExportPage(
                lastId.toString(), bound.toString(), limit);
        if (products.isEmpty()) {
            return List.of();
        }

        List<UUID> productIds = products.stream().map(ProductEntity::getProductId).toList();
        Map<UUID, List<SkuEntity>> skusByProduct =
                skuRepository.findByProductIdInOrderBySkuCodeAsc(productIds).stream()
                        .collect(Collectors.groupingBy(SkuEntity::getProductId));
        Map<UUID, List<ProductMediaEntity>> mediaByProduct = assembler.groupMediaByProduct(
                mediaRepository.findByProductIdInOrderBySortOrderAscMediaIdAsc(productIds));

        return products.stream()
                .map(product -> assembler.toProduct(product,
                        skusByProduct.getOrDefault(product.getProductId(), List.of()),
                        mediaByProduct.getOrDefault(product.getProductId(), List.of())))
                .toList();
    }

    /**
     * Một trang Category theo keyset, kèm path, {@code is_leaf} và {@code facet_definitions}
     * dẫn xuất khi đọc. Category được đọc trước, facet đọc sau: nếu một lần đổi tên attribute
     * chen vào giữa, trang mang tên mới gắn revision cũ và event revision mới được replay sau
     * đó ghi đè bằng cùng nội dung. Đọc ngược thứ tự có thể để tên cũ gắn revision mới, và
     * replay cùng revision là no-op nên tên cũ sẽ còn mãi.
     */
    public List<CategoryDtos.CategoryExportResponse> categories(UUID lastId, UUID bound, int limit) {
        List<CategoryEntity> categories = categoryRepository.findExportPage(
                lastId.toString(), bound.toString(), limit);
        Map<UUID, List<FacetDefinition>> facets = facetResolver.of(
                categories.stream().map(CategoryEntity::getCategoryId).toList());
        return categories.stream()
                .map(category -> assembler.toCategoryExport(category,
                        categoryTree.path(category),
                        categoryTree.isLeaf(category.getCategoryId()),
                        facets.getOrDefault(category.getCategoryId(), List.of())))
                .toList();
    }
}
