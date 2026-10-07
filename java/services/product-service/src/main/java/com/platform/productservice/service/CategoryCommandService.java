package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.entity.CategoryAttributeId;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.enums.CategoryChangeKind;
import com.platform.productservice.infrastructure.event.CatalogEventPublisher;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.service.support.CategoryTreeService;
import com.platform.productservice.web.dto.CategoryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Command Category và mapping category–attribute (WF-PRODUCT-003).
 *
 * <p>Rename, đổi slug hoặc đổi parent làm path dẫn xuất của cả subtree đổi,
 * nên Product ghi thêm {@code catalog-reindex-requested} trong cùng transaction
 * thay vì fan-out event cho mọi descendant (ADR-010).
 */
@Service
@RequiredArgsConstructor
public class CategoryCommandService {

    private final CategoryRepository categoryRepository;
    private final CategoryAttributeRepository categoryAttributeRepository;
    private final AttributeRepository attributeRepository;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryTreeService categoryTree;
    private final CatalogAssembler assembler;
    private final CatalogEventPublisher eventPublisher;

    @Transactional
    public CategoryDtos.CategoryResponse create(CategoryDtos.CreateCategoryRequest request) {
        Instant now = Instant.now();
        categoryTree.validateParent(null, request.parentId());
        if (categoryRepository.existsBySlug(request.slug())) {
            throw DomainException.of(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Category slug " + request.slug() + " is already used");
        }
        boolean parentWasLeaf = request.parentId() != null && categoryTree.isLeaf(request.parentId());

        CategoryEntity category = new CategoryEntity();
        category.setCategoryId(Uuids.next());
        category.setParentId(request.parentId());
        category.setName(request.name());
        category.setSlug(request.slug());
        category.setCategoryRevision(1L);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        categoryRepository.save(category);

        publishSnapshot(category, CategoryChangeKind.CATEGORY_CREATED, now);
        if (parentWasLeaf) {
            // is_leaf của parent vừa đổi, Search cần snapshot mới của parent.
            republishParent(request.parentId(), now);
        }
        return assembler.toCategory(category, attributesOf(category.getCategoryId()));
    }

    @Transactional
    public CategoryDtos.CategoryResponse replace(UUID categoryId,
                                                 CategoryDtos.ReplaceCategoryRequest request) {
        Instant now = Instant.now();
        CategoryEntity category = lock(categoryId);
        categoryTree.validateParent(categoryId, request.parentId());
        if (categoryRepository.existsBySlugAndCategoryIdNot(request.slug(), categoryId)) {
            throw DomainException.of(ErrorCode.SLUG_ALREADY_EXISTS,
                    "Category slug " + request.slug() + " is already used");
        }

        UUID previousParentId = category.getParentId();
        boolean parentChanged = !Objects.equals(previousParentId, request.parentId());
        boolean pathChanged = parentChanged
                || !category.getName().equals(request.name())
                || !category.getSlug().equals(request.slug());
        boolean newParentWasLeaf = parentChanged && request.parentId() != null
                && categoryTree.isLeaf(request.parentId());

        category.setParentId(request.parentId());
        category.setName(request.name());
        category.setSlug(request.slug());
        category.bumpRevision(now);

        publishSnapshot(category, CategoryChangeKind.CATEGORY_UPDATED, now);
        if (pathChanged) {
            eventPublisher.catalogReindexRequested(categoryTree.rootIdOf(category), categoryId,
                    category.getCategoryRevision(), now);
        }
        if (parentChanged) {
            if (previousParentId != null && categoryTree.isLeaf(previousParentId)) {
                republishParent(previousParentId, now);
            }
            if (newParentWasLeaf) {
                republishParent(request.parentId(), now);
            }
        }
        return assembler.toCategory(category, attributesOf(categoryId));
    }

    /** Chỉ xóa được category không còn category con và không còn Product (PRODUCT-BR-011). */
    @Transactional
    public void delete(UUID categoryId) {
        Instant now = Instant.now();
        CategoryEntity category = lock(categoryId);
        if (categoryRepository.existsByParentId(categoryId)) {
            throw DomainException.of(ErrorCode.CATEGORY_NOT_EMPTY,
                    "Category " + categoryId + " still has child categories");
        }
        if (productRepository.existsByCategoryId(categoryId)) {
            throw DomainException.of(ErrorCode.CATEGORY_NOT_EMPTY,
                    "Category " + categoryId + " still has products");
        }

        UUID parentId = category.getParentId();
        long tombstoneRevision = category.getCategoryRevision() + 1;
        categoryAttributeRepository.deleteAll(categoryAttributeRepository.findByIdCategoryId(categoryId));
        eventPublisher.categoryDeleted(categoryId, tombstoneRevision, now);
        categoryRepository.delete(category);

        if (parentId != null && !categoryRepository.existsByParentId(parentId)) {
            republishParent(parentId, now);
        }
    }

    /** PUT idempotent ở cấp resource mapping; trả về true khi mapping vừa được tạo. */
    @Transactional
    public UpsertResult putAttribute(UUID categoryId,
                                     UUID attributeId,
                                     CategoryDtos.PutCategoryAttributeRequest request) {
        Instant now = Instant.now();
        CategoryEntity category = lock(categoryId);
        AttributeEntity attribute = requireAttribute(attributeId);
        boolean required = Boolean.TRUE.equals(request.isRequired());
        boolean variant = Boolean.TRUE.equals(request.isVariant());

        validateMappingAgainstExistingProducts(categoryId, attribute.getCode(), required, variant);

        CategoryAttributeId id = new CategoryAttributeId(categoryId, attributeId);
        Optional<CategoryAttributeEntity> existing = categoryAttributeRepository.findById(id);
        CategoryAttributeEntity mapping = existing.orElseGet(() -> {
            CategoryAttributeEntity created = new CategoryAttributeEntity();
            created.setId(id);
            return created;
        });
        mapping.setRequired(required);
        mapping.setVariant(variant);
        categoryAttributeRepository.save(mapping);

        category.bumpRevision(now);
        publishSnapshot(category, CategoryChangeKind.CATEGORY_ATTRIBUTE_CHANGED, now);
        return new UpsertResult(assembler.toCategoryAttribute(mapping, attribute), existing.isEmpty());
    }

    @Transactional
    public void deleteAttribute(UUID categoryId, UUID attributeId) {
        Instant now = Instant.now();
        CategoryEntity category = lock(categoryId);
        AttributeEntity attribute = requireAttribute(attributeId);
        CategoryAttributeId id = new CategoryAttributeId(categoryId, attributeId);
        CategoryAttributeEntity mapping = categoryAttributeRepository.findById(id)
                .orElseThrow(() -> DomainException.of(ErrorCode.ATTRIBUTE_NOT_FOUND,
                        "Attribute " + attributeId + " is not mapped to category " + categoryId));

        long usedByProducts = productRepository.countInCategoryUsingAttribute(categoryId, attribute.getCode());
        long usedBySkus = skuRepository.countInCategoryUsingVariantAttribute(categoryId, attribute.getCode());
        if (usedByProducts > 0 || usedBySkus > 0) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Attribute " + attribute.getCode() + " is still used by products in this category");
        }

        categoryAttributeRepository.delete(mapping);
        category.bumpRevision(now);
        publishSnapshot(category, CategoryChangeKind.CATEGORY_ATTRIBUTE_CHANGED, now);
    }

    /**
     * Mapping mới không được làm Product hiện hữu trong category trở nên không
     * hợp lệ (contracts/http/product/v1/openapi.yaml — putCategoryAttribute).
     */
    private void validateMappingAgainstExistingProducts(UUID categoryId,
                                                        String code,
                                                        boolean required,
                                                        boolean variant) {
        if (required) {
            long missing = variant
                    ? skuRepository.countInCategoryMissingVariantAttribute(categoryId, code)
                    : productRepository.countInCategoryMissingAttribute(categoryId, code);
            if (missing > 0) {
                throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                        missing + (variant ? " SKU" : " product") + " row(s) in this category have no value for "
                                + code);
            }
        }
        long wrongSide = variant
                ? productRepository.countInCategoryUsingAttribute(categoryId, code)
                : skuRepository.countInCategoryUsingVariantAttribute(categoryId, code);
        if (wrongSide > 0) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Attribute " + code + " is already stored on the "
                            + (variant ? "product" : "SKU") + " side in this category");
        }
    }

    private void republishParent(UUID parentId, Instant now) {
        CategoryEntity parent = categoryTree.require(parentId);
        parent.bumpRevision(now);
        publishSnapshot(parent, CategoryChangeKind.CATEGORY_UPDATED, now);
    }

    private void publishSnapshot(CategoryEntity category, CategoryChangeKind changeKind, Instant now) {
        eventPublisher.categoryUpserted(category,
                categoryTree.path(category),
                categoryTree.isLeaf(category.getCategoryId()),
                changeKind,
                now);
    }

    private CategoryEntity lock(UUID categoryId) {
        return categoryRepository.lockById(categoryId)
                .orElseThrow(() -> DomainException.of(ErrorCode.CATEGORY_NOT_FOUND,
                        "Category " + categoryId + " does not exist"));
    }

    private AttributeEntity requireAttribute(UUID attributeId) {
        return attributeRepository.findById(attributeId)
                .orElseThrow(() -> DomainException.of(ErrorCode.ATTRIBUTE_NOT_FOUND,
                        "Attribute " + attributeId + " does not exist"));
    }

    private List<CategoryDtos.CategoryAttributeResponse> attributesOf(UUID categoryId) {
        List<CategoryAttributeEntity> mappings = categoryAttributeRepository.findByIdCategoryId(categoryId);
        return mappings.stream()
                .map(mapping -> attributeRepository.findById(mapping.getId().getAttributeId())
                        .map(attribute -> assembler.toCategoryAttribute(mapping, attribute))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public record UpsertResult(CategoryDtos.CategoryAttributeResponse mapping, boolean created) {
    }
}
