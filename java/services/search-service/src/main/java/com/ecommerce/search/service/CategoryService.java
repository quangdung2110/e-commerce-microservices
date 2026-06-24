package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.dto.CategoryTreeResponse;
import com.ecommerce.search.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Service for the category tree API ({@code GET /api/v1/search/categories}).
 *
 * <p>Returns a flat list of category nodes (sorted hierarchically by level)
 * from which the frontend can reconstruct the full tree. Each node includes
 * a real-time {@code product_count}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repository;
    private final CategoryQueryBuilder queryBuilder;

    /**
     * Retrieve the full category tree as a flat, sorted list.
     *
     * <p>The list is sorted by {@code level} ascending (top-level first), then
     * by {@code category_id} within the same level. The frontend links nodes
     * via {@code parentId} references to reconstruct the tree.
     *
     * @return a {@link CategoryTreeResponse} containing all visible categories
     */
    public CategoryTreeResponse getCategoryTree() {
        log.debug("Fetching category tree");

        SearchRequest request = queryBuilder.build();
        SearchResponse<CategoryDocument> response = repository.search(request);

        List<CategoryTreeResponse.CategoryNode> nodes = response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .map(doc -> CategoryTreeResponse.CategoryNode.builder()
                .categoryId(doc.categoryId())
                .name(doc.name())
                .slug(doc.slug())
                .parentId(doc.parentId())
                .level(doc.level())
                .isLeaf(doc.isLeaf())
                .path(doc.path())
                .productCount(doc.productCount())
                .build())
            .toList();

        log.debug("Category tree returned {} nodes", nodes.size());
        return CategoryTreeResponse.builder()
            .categories(nodes)
            .build();
    }
}
