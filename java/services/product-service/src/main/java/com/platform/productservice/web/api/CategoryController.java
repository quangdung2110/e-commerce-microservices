package com.platform.productservice.web.api;

import com.platform.productservice.service.CategoryCommandService;
import com.platform.productservice.service.CategoryQueryService;
import com.platform.productservice.web.dto.CategoryDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Quản trị category tree và mapping category–attribute.
 *
 * <p>Rename, đổi slug hoặc đổi parent phát thêm
 * {@code catalog-reindex-requested}; Search coalesce rồi full reindex thay vì
 * Product fan-out cả subtree (ADR-010).
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryCommandService commandService;
    private final CategoryQueryService queryService;

    @GetMapping
    public CategoryDtos.CategoryListResponse list(
            @RequestParam(name = "parent_id", required = false) UUID parentId,
            @RequestParam(name = "root_only", defaultValue = "false") boolean rootOnly) {
        return queryService.list(parentId, rootOnly);
    }

    @PostMapping
    public ResponseEntity<CategoryDtos.CategoryResponse> create(
            @Valid @RequestBody CategoryDtos.CreateCategoryRequest request) {
        CategoryDtos.CategoryResponse category = commandService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/categories/" + category.categoryId()))
                .body(category);
    }

    @GetMapping("/{category_id}")
    public CategoryDtos.CategoryResponse get(@PathVariable("category_id") UUID categoryId) {
        return queryService.get(categoryId);
    }

    @PutMapping("/{category_id}")
    public CategoryDtos.CategoryResponse replace(
            @PathVariable("category_id") UUID categoryId,
            @Valid @RequestBody CategoryDtos.ReplaceCategoryRequest request) {
        return commandService.replace(categoryId, request);
    }

    @DeleteMapping("/{category_id}")
    public ResponseEntity<Void> delete(@PathVariable("category_id") UUID categoryId) {
        commandService.delete(categoryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{category_id}/attributes")
    public CategoryDtos.CategoryAttributeListResponse listAttributes(
            @PathVariable("category_id") UUID categoryId) {
        return queryService.listAttributes(categoryId);
    }

    @PutMapping("/{category_id}/attributes/{attribute_id}")
    public ResponseEntity<CategoryDtos.CategoryAttributeResponse> putAttribute(
            @PathVariable("category_id") UUID categoryId,
            @PathVariable("attribute_id") UUID attributeId,
            @Valid @RequestBody CategoryDtos.PutCategoryAttributeRequest request) {
        CategoryCommandService.UpsertResult result =
                commandService.putAttribute(categoryId, attributeId, request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.mapping());
    }

    @DeleteMapping("/{category_id}/attributes/{attribute_id}")
    public ResponseEntity<Void> deleteAttribute(@PathVariable("category_id") UUID categoryId,
                                                @PathVariable("attribute_id") UUID attributeId) {
        commandService.deleteAttribute(categoryId, attributeId);
        return ResponseEntity.noContent().build();
    }
}
