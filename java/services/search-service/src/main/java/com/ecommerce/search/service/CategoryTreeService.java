package com.ecommerce.search.service;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code WF-SEARCH-003} — builds the sidebar tree.
 *
 * <p>{@code product_count} is read straight off the document; it is materialised by the
 * recompute job and can lag behind search results, which is exactly why the response also
 * carries {@code product_count_updated_at} (ADR-008).
 *
 * <p>An orphan node — one whose {@code parent_id} points at a category that is absent or
 * tombstoned — is dropped from the tree and counted, rather than being allowed to break
 * the whole response.
 */
@Service
public class CategoryTreeService {

    private static final Logger log = LoggerFactory.getLogger(CategoryTreeService.class);

    private final CategoryRepository repository;
    private final SearchMetrics metrics;
    private final com.ecommerce.search.config.StorefrontProperties storefront;

    public CategoryTreeService(CategoryRepository repository, SearchMetrics metrics) {
        this(repository, metrics, new com.ecommerce.search.config.StorefrontProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CategoryTreeService(CategoryRepository repository, SearchMetrics metrics,
                               com.ecommerce.search.config.StorefrontProperties storefront) {
        this.repository = repository;
        this.metrics = metrics;
        this.storefront = storefront;
    }

    public SearchDtos.CategoryTree tree() throws IOException {
        List<CategoryDocument> documents = repository.findAllVisible().stream()
                .filter(doc -> !storefront.getExcludedCategoryIds().contains(doc.categoryId()))
                .filter(doc -> doc.ancestorIds() == null || doc.ancestorIds().stream().noneMatch(storefront.getExcludedCategoryIds()::contains))
                .toList();

        Map<String, CategoryDocument> byId = new HashMap<>();
        documents.forEach(doc -> byId.put(doc.categoryId(), doc));

        Map<String, List<CategoryDocument>> childrenByParent = new LinkedHashMap<>();
        List<CategoryDocument> roots = new ArrayList<>();

        for (CategoryDocument doc : sorted(documents)) {
            String parentId = doc.parentId();
            if (parentId == null || parentId.isBlank()) {
                roots.add(doc);
            } else if (byId.containsKey(parentId)) {
                childrenByParent.computeIfAbsent(parentId, unused -> new ArrayList<>()).add(doc);
            } else {
                metrics.orphanCategoryNode();
                log.warn("dropping orphan category node {}: parent {} is absent from the projection",
                        doc.categoryId(), parentId);
            }
        }

        List<SearchDtos.CategoryNode> nodes = roots.stream()
                .map(root -> toNode(root, childrenByParent))
                .toList();
        return SearchDtos.CategoryTree.of(nodes);
    }

    private SearchDtos.CategoryNode toNode(CategoryDocument doc,
                                           Map<String, List<CategoryDocument>> childrenByParent) {
        List<SearchDtos.CategoryNode> children = childrenByParent
                .getOrDefault(doc.categoryId(), List.of()).stream()
                .map(child -> toNode(child, childrenByParent))
                .toList();
        return new SearchDtos.CategoryNode(
                doc.categoryId(),
                doc.name(),
                doc.slug(),
                doc.productCountOrZero(),
                doc.productCountUpdatedAt(),
                children);
    }

    /** Parents before children, then a stable order inside each level. */
    private static List<CategoryDocument> sorted(List<CategoryDocument> documents) {
        return documents.stream()
                .sorted(Comparator
                        .comparingInt(CategoryDocument::levelOrZero)
                        .thenComparing(CategoryDocument::categoryId))
                .toList();
    }
}
