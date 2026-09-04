package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.web.dto.CategoryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read path của category tree cho màn hình quản trị. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categoryRepository;
    private final CategoryAttributeRepository categoryAttributeRepository;
    private final AttributeRepository attributeRepository;
    private final CatalogAssembler assembler;

    public CategoryDtos.CategoryListResponse list(UUID parentId, boolean rootOnly) {
        if (parentId != null && rootOnly) {
            throw DomainException.of(ErrorCode.INVALID_REQUEST,
                    "parent_id and root_only=true cannot be combined");
        }
        List<CategoryEntity> categories;
        if (parentId != null) {
            categories = categoryRepository.findByParentIdOrderByNameAsc(parentId);
        } else if (rootOnly) {
            categories = categoryRepository.findByParentIdIsNullOrderByNameAsc();
        } else {
            categories = categoryRepository.findAllByOrderByNameAsc();
        }
        return new CategoryDtos.CategoryListResponse(
                categories.stream()
                        .map(category -> assembler.toCategory(category, attributesOf(category.getCategoryId())))
                        .toList());
    }

    public CategoryDtos.CategoryResponse get(UUID categoryId) {
        CategoryEntity category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> DomainException.of(ErrorCode.CATEGORY_NOT_FOUND,
                        "Category " + categoryId + " does not exist"));
        return assembler.toCategory(category, attributesOf(categoryId));
    }

    public CategoryDtos.CategoryAttributeListResponse listAttributes(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw DomainException.of(ErrorCode.CATEGORY_NOT_FOUND,
                    "Category " + categoryId + " does not exist");
        }
        return new CategoryDtos.CategoryAttributeListResponse(attributesOf(categoryId));
    }

    private List<CategoryDtos.CategoryAttributeResponse> attributesOf(UUID categoryId) {
        List<CategoryAttributeEntity> mappings = categoryAttributeRepository.findByIdCategoryId(categoryId);
        if (mappings.isEmpty()) {
            return List.of();
        }
        Map<UUID, AttributeEntity> attributes = attributeRepository
                .findByAttributeIdIn(mappings.stream().map(mapping -> mapping.getId().getAttributeId()).toList())
                .stream()
                .collect(Collectors.toMap(AttributeEntity::getAttributeId, Function.identity()));
        return mappings.stream()
                .filter(mapping -> attributes.containsKey(mapping.getId().getAttributeId()))
                .map(mapping -> assembler.toCategoryAttribute(
                        mapping, attributes.get(mapping.getId().getAttributeId())))
                .toList();
    }
}
