package com.ecommerce.search.controller.api;

import com.ecommerce.search.dto.CategoryTreeResponse;
import com.ecommerce.search.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the category tree API
 * ({@code GET /api/v1/search/categories}).
 *
 * <p>Returns the full category tree as a flat list with {@code parent_id}
 * references. The frontend reconstructs the tree structure for navigation
 * sidebars and category browsing. Each node includes a real-time
 * {@code product_count}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * Get all visible categories sorted hierarchically.
     *
     * @return a {@link CategoryTreeResponse} with the full category list
     */
    @GetMapping
    public ResponseEntity<CategoryTreeResponse> getCategories() {
        log.debug("GET /api/v1/search/categories");
        CategoryTreeResponse response = categoryService.getCategoryTree();
        return ResponseEntity.ok(response);
    }
}
