package com.platform.productservice.service;

import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.infrastructure.persistence.CategoryRepository;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.CategoryTreeService;
import com.platform.productservice.service.support.CursorCodec;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Export nội bộ cho Search full reindex (WF-PRODUCT-003, ADR-010).
 *
 * <p>Trang đầu chụp một upper bound UUIDv7; các trang sau đi theo ID tăng dần
 * tới bound đó nên export luôn kết thúc. Đây không phải snapshot bất biến:
 * Search phải chạy song song catalog event consumer để nhận thay đổi phát sinh
 * trong lúc export.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogExportService {

    private static final UUID FIRST_ID = new UUID(0L, 0L);

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryTreeService categoryTree;
    private final CatalogAssembler assembler;
    private final CursorCodec cursorCodec;

    public ProductDtos.ProductExportPageResponse exportProducts(String cursor, int limit) {
        CursorCodec.ExportCursor position = position(cursor);
        List<ProductEntity> products = productRepository.findExportPage(
                position.lastId().toString(), position.bound().toString(), limit);
        if (products.isEmpty()) {
            return new ProductDtos.ProductExportPageResponse(List.of(), null);
        }

        List<UUID> productIds = products.stream().map(ProductEntity::getProductId).toList();
        Map<UUID, List<SkuEntity>> skusByProduct =
                skuRepository.findByProductIdInOrderBySkuCodeAsc(productIds).stream()
                        .collect(Collectors.groupingBy(SkuEntity::getProductId));
        Map<UUID, List<ProductMediaEntity>> mediaByProduct = assembler.groupMediaByProduct(
                mediaRepository.findByProductIdInOrderBySortOrderAscMediaIdAsc(productIds));

        List<ProductDtos.ProductResponse> page = products.stream()
                .map(product -> assembler.toProduct(product,
                        skusByProduct.getOrDefault(product.getProductId(), List.of()),
                        mediaByProduct.getOrDefault(product.getProductId(), List.of())))
                .toList();

        String nextCursor = products.size() < limit ? null : cursorCodec.encodeExport(
                products.get(products.size() - 1).getProductId(), position.bound());
        return new ProductDtos.ProductExportPageResponse(page, nextCursor);
    }

    /** Search đọc export category trước export Product khi xử lý reindex request. */
    public CategoryDtos.CategoryExportPageResponse exportCategories(String cursor, int limit) {
        CursorCodec.ExportCursor position = position(cursor);
        List<CategoryEntity> categories = categoryRepository.findExportPage(
                position.lastId().toString(), position.bound().toString(), limit);
        if (categories.isEmpty()) {
            return new CategoryDtos.CategoryExportPageResponse(List.of(), null);
        }

        List<CategoryDtos.CategoryExportResponse> page = categories.stream()
                .map(category -> assembler.toCategoryExport(category,
                        categoryTree.path(category),
                        categoryTree.isLeaf(category.getCategoryId())))
                .toList();

        String nextCursor = categories.size() < limit ? null : cursorCodec.encodeExport(
                categories.get(categories.size() - 1).getCategoryId(), position.bound());
        return new CategoryDtos.CategoryExportPageResponse(page, nextCursor);
    }

    private CursorCodec.ExportCursor position(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorCodec.ExportCursor(FIRST_ID, Uuids.next());
        }
        return cursorCodec.decodeExport(cursor);
    }
}
