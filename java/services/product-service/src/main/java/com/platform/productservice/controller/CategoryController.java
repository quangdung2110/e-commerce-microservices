package com.platform.productservice.controller;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.dto.mapper.CategoryMapper;
import com.platform.productservice.dto.request.CreateCategoryRequest;
import com.platform.productservice.dto.request.UpdateCategoryRequest;
import com.platform.productservice.dto.response.CategoryResponse;
import com.platform.productservice.dto.response.CategoryTreeResponse;
import com.platform.productservice.port.inbound.CategoryServicePort;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin REST controller for category management.
 * All endpoints are prefixed with /api/v1/categories.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Validated
public class CategoryController {

    private static final Logger log = LoggerFactory.getLogger(CategoryController.class);

    private final CategoryServicePort categoryService;
    private final CategoryMapper categoryMapper;
    private final CategoryRepository categoryRepository;

    public CategoryController(
            CategoryServicePort categoryService,
            CategoryMapper categoryMapper,
            CategoryRepository categoryRepository) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
        this.categoryRepository = categoryRepository;
    }

    @GetMapping("/tree")
    public ResponseEntity<CategoryTreeResponse> getCategoryTree() {
        log.info("REST: getCategoryTree");

        List<Category> roots = categoryService.findTree();
        Map<String, List<Category>> childrenMap = buildChildrenMap();

        CategoryTreeResponse response = categoryMapper.toTreeResponse(roots, childrenMap);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable String id) {
        log.info("REST: getCategory - categoryId={}", id);

        Category category = categoryService.findById(id);
        CategoryResponse response = categoryMapper.toResponse(category);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listCategories(
            @RequestParam(required = false) String parentId) {

        log.info("REST: listCategories - parentId={}", parentId);

        List<Category> categories;
        if (parentId != null && !parentId.isBlank()) {
            categories = categoryRepository.findChildren(
                    com.platform.productservice.domain.vo.CategoryId.of(parentId));
        } else {
            categories = categoryRepository.findRootCategories();
        }

        List<CategoryResponse> response = categories.stream()
                .map(categoryMapper::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {

        log.info("REST: createCategory - name={}, parentId={}", request.name(), request.parentId());

        Category category = categoryMapper.toDomain(request);
        Category created = categoryService.create(category);
        CategoryResponse response = categoryMapper.toResponse(created);

        log.info("REST: createCategory - created categoryId={}", created.getId().getValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable String id,
            @Valid @RequestBody UpdateCategoryRequest request) {

        log.info("REST: updateCategory - categoryId={}", id);

        Category category = categoryService.findById(id);
        categoryMapper.toDomainUpdate(request, category);
        Category updated = categoryService.update(id, category);
        CategoryResponse response = categoryMapper.toResponse(updated);

        log.info("REST: updateCategory - updated categoryId={}", id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        log.info("REST: deleteCategory - categoryId={}", id);

        categoryService.delete(id);

        log.info("REST: deleteCategory - deleted categoryId={}", id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, List<Category>> buildChildrenMap() {
        Map<String, List<Category>> childrenMap = new HashMap<>();
        List<Category> allCategories = categoryRepository.findRootCategories();

        for (Category root : allCategories) {
            collectChildren(root.getId().getValue(), childrenMap);
        }

        return childrenMap;
    }

    private void collectChildren(String parentId, Map<String, List<Category>> childrenMap) {
        List<Category> children = categoryRepository.findChildren(
                com.platform.productservice.domain.vo.CategoryId.of(parentId));
        if (children != null && !children.isEmpty()) {
            childrenMap.put(parentId, children);
            for (Category child : children) {
                collectChildren(child.getId().getValue(), childrenMap);
            }
        }
    }
}
