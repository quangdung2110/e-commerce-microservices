package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.infrastructure.persistence.CategoryRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Giá trị dẫn xuất của cây category: {@code path}, {@code is_leaf} và root
 * ancestor. Schema không lưu các giá trị này (docs/product/data.md §3.1).
 */
@Component
@RequiredArgsConstructor
public class CategoryTreeService {

    /** Trùng maxItems của category.path trong catalog-common.schema.json. */
    private static final int MAX_DEPTH = 64;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryEntity require(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> DomainException.of(ErrorCode.CATEGORY_NOT_FOUND,
                        "Category " + categoryId + " does not exist"));
    }

    /** Path từ root tới chính category, dùng cho Category snapshot và export. */
    public List<CategoryEntity> path(CategoryEntity category) {
        List<CategoryEntity> path = new ArrayList<>();
        CategoryEntity current = category;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            path.add(current);
            UUID parentId = current.getParentId();
            current = parentId == null ? null : categoryRepository.findById(parentId).orElse(null);
            depth++;
        }
        Collections.reverse(path);
        return path;
    }

    /** Root ancestor; là {@code root_category_id} của catalog-reindex-requested. */
    public UUID rootIdOf(CategoryEntity category) {
        List<CategoryEntity> path = path(category);
        return path.isEmpty() ? category.getCategoryId() : path.get(0).getCategoryId();
    }

    public boolean isLeaf(UUID categoryId) {
        return !categoryRepository.existsByParentId(categoryId);
    }

    /** Product chỉ được gán vào category lá đang tồn tại (PRODUCT-BR-001). */
    public CategoryEntity requireLeaf(UUID categoryId) {
        CategoryEntity category = require(categoryId);
        if (!isLeaf(categoryId)) {
            throw DomainException.of(ErrorCode.CATEGORY_NOT_LEAF,
                    "Category " + categoryId + " has child categories");
        }
        return category;
    }

    /**
     * Parent mới không được là chính nó hay hậu duệ của nó, và không được đang
     * chứa Product — nhận thêm con sẽ làm parent hết là lá và phá vỡ
     * PRODUCT-BR-001 với Product hiện hữu.
     */
    public void validateParent(UUID categoryId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        if (newParentId.equals(categoryId)) {
            throw DomainException.of(ErrorCode.CATEGORY_CYCLE_DETECTED,
                    "Category cannot be its own parent");
        }
        CategoryEntity parent = require(newParentId);
        if (productRepository.existsByCategoryId(newParentId)) {
            throw DomainException.of(ErrorCode.CATEGORY_NOT_LEAF,
                    "Category " + newParentId + " already holds products and cannot get children");
        }
        if (categoryId != null) {
            UUID ancestorId = parent.getParentId();
            int depth = 0;
            while (ancestorId != null && depth < MAX_DEPTH) {
                if (ancestorId.equals(categoryId)) {
                    throw DomainException.of(ErrorCode.CATEGORY_CYCLE_DETECTED,
                            "Category " + newParentId + " is a descendant of " + categoryId);
                }
                ancestorId = categoryRepository.findById(ancestorId)
                        .map(CategoryEntity::getParentId)
                        .orElse(null);
                depth++;
            }
        }
    }
}
