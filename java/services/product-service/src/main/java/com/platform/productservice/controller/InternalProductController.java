package com.platform.productservice.controller;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.dto.mapper.CategoryMapper;
import com.platform.productservice.dto.mapper.ProductMapper;
import com.platform.productservice.dto.response.CategoryTreeResponse;
import com.platform.productservice.dto.response.PagedResponse;
import com.platform.productservice.dto.response.ProductResponse;
import com.platform.productservice.port.inbound.CategoryServicePort;
import com.platform.productservice.port.inbound.ProductServicePort;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Internal REST API controller for service-to-service communication.
 * These endpoints are used by other services (Search, Reindexer, etc.)
 * and should be secured with @PreAuthorize("hasRole('INTERNAL')") or mTLS.
 */
@RestController
@RequestMapping("/api/v1/internal/products")
public class InternalProductController {

    private static final Logger log = LoggerFactory.getLogger(InternalProductController.class);

    private final CategoryServicePort categoryService;
    private final ProductServicePort productService;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final CategoryRepository categoryRepository;

    public InternalProductController(
            CategoryServicePort categoryService,
            ProductServicePort productService,
            CategoryMapper categoryMapper,
            ProductMapper productMapper,
            CategoryRepository categoryRepository) {
        this.categoryService = categoryService;
        this.productService = productService;
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Returns the full category tree for Search Service sync.
     */
    @GetMapping("/categories/tree")
    public ResponseEntity<CategoryTreeResponse> getCategoryTreeSnapshot() {
        log.info("Internal: getCategoryTreeSnapshot");

        List<Category> roots = categoryService.findTree();
        Map<String, List<Category>> childrenMap = buildChildrenMap();

        CategoryTreeResponse response = categoryMapper.toTreeResponse(roots, childrenMap);

        return ResponseEntity.ok(response);
    }

    /**
     * Paginated product export for reindexing by Search Service.
     */
    @GetMapping("/export")
    public ResponseEntity<PagedResponse<ProductResponse>> exportProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String updatedSince) {

        log.info("Internal: exportProducts - page={}, size={}, updatedSince={}", page, size, updatedSince);

        ProductServicePort.ProductSearchCriteria criteria = new ProductServicePort.ProductSearchCriteria(
                null,
                null,
                null,
                page - 1,
                Math.min(size, 100),
                "updatedAt,asc"
        );

        Page<Product> productPage = productService.search(criteria);

        List<ProductResponse> items = productPage.getContent().stream()
                .map(productMapper::toSummaryResponse)
                .collect(Collectors.toList());

        PagedResponse<ProductResponse> response = PagedResponse.of(
                items,
                page,
                size,
                productPage.getTotalElements()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Returns a full product snapshot for retry replayer.
     */
    @GetMapping("/{productId}/snapshot")
    public ResponseEntity<ProductResponse> getProductSnapshot(@PathVariable String productId) {
        log.info("Internal: getProductSnapshot - productId={}", productId);

        Product product = productService.findById(productId);
        ProductResponse response = productMapper.toResponse(product);

        return ResponseEntity.ok(response);
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
