# Module 05: Public API Controllers & Services

> **Status:** Module 5 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 04 (Search Query Builders)
> **Dependents:** None (depends only on 04)
> **Estimated Effort:** 1.5 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

This module implements the 4 public API endpoints + their services:
- `GET /api/v1/search/products` — search với cursor pagination
- `GET /api/v1/search/suggest` — autocomplete
- `GET /api/v1/search/categories` — cây danh mục
- `GET /api/v1/search/facets/{category_id}` — facets

Services wrap repositories + query builders, transform ES responses → API DTOs.

---

## 2. ProductSearchService

### 2.1 File: `service/ProductSearchService.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/ProductSearchService.java`

- [ ] Create `@Service` class
- [ ] Inject: `ProductSearchRepository`, `ProductQueryBuilder`, `SortMapper`, `DiscountCalculator`, `SearchProperties`, `CursorCodec` (Module 06)
- [ ] Method: `ProductSearchResponse search(SearchParams params)`
- [ ] Parse cursor → `List<FieldValue> searchAfter`
- [ ] Build query, execute search
- [ ] Transform ES hits → `ProductSearchItem` (apply `DiscountCalculator`)
- [ ] Extract aggregations → `FacetResponse`
- [ ] Build next cursor từ last hit sort values
- [ ] Timeout: `properties.es.queryTimeoutProductMs`

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHits;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.*;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.util.CursorCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ProductSearchRepository repository;
    private final ProductQueryBuilder queryBuilder;
    private final SortMapper sortMapper;
    private final DiscountCalculator discountCalculator;
    private final CursorCodec cursorCodec;
    private final SearchProperties properties;

    public ProductSearchResponse search(SearchParams params) {
        log.debug("Searching products: q={}, category={}, sort={}, size={}",
            params.q(), params.category(), params.sort(), params.size());

        // 1. Decode cursor
        List<FieldValue> searchAfter = null;
        if (params.cursor() != null && !params.cursor().isBlank()) {
            try {
                searchAfter = cursorCodec.decode(params.cursor());
            } catch (Exception e) {
                throw new com.ecommerce.search.domain.exception.InvalidCursorException(
                    "Invalid cursor format: " + e.getMessage());
            }
        }

        // 2. Build query
        SearchRequest request = queryBuilder.build(params, searchAfter);

        // 3. Execute search
        SearchResponse<ProductDocument> response;
        try {
            response = repository.search(request);
        } catch (SearchException e) {
            if (e.getErrorCode() == ErrorCode.ELASTICSEARCH_UNAVAILABLE) {
                throw new SearchException(ErrorCode.SEARCH_TIMEOUT, "Search timeout", e);
            }
            throw e;
        }

        // 4. Transform hits
        List<ProductSearchItem> items = response.hits().hits().stream()
            .map(this::toItem)
            .toList();

        // 5. Extract totals
        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        // 6. Build next cursor
        String nextCursor = null;
        if (!items.isEmpty() && items.size() == params.size()) {
            Hit<ProductDocument> lastHit = response.hits().hits().get(response.hits().hits().size() - 1);
            if (lastHit.sort() != null && !lastHit.sort().isEmpty()) {
                nextCursor = cursorCodec.encode(lastHit.sort());
            }
        }

        // 7. Extract facets
        FacetResponse facets = extractFacets(response.aggregations());

        return ProductSearchResponse.builder()
            .total(total)
            .totalSkus(total)
            .totalProducts(total)
            .size(params.size())
            .items(items)
            .nextCursor(nextCursor)
            .facets(facets)
            .paginationNote("next_cursor = null kết thúc pagination. Mỗi item có thể kèm nhiều SKU trong inner_hits")
            .build();
    }

    private ProductSearchItem toItem(Hit<ProductDocument> hit) {
        ProductDocument doc = hit.source();
        if (doc == null) {
            return ProductSearchItem.builder()
                .productId(hit.id())
                .build();
        }

        int discountPct = DiscountCalculator.calculate(doc.originalPrice(), doc.bestPrice());

        // Extract matching_skus from inner_hits
        List<ProductSearchItem.MatchingSku> matchingSkus = new ArrayList<>();
        if (hit.innerHits() != null && hit.innerHits().containsKey("matching_skus")) {
            InnerHits innerHits = hit.innerHits().get("matching_skus");
            for (Hit<ProductDocument> innerHit : innerHits.hits().hits()) {
                if (innerHit.source() != null) {
                    matchingSkus.add(ProductSearchItem.MatchingSku.builder()
                        .skuId(innerHit.source().skuId())
                        .bestPrice(innerHit.source().bestPrice())
                        .variantAttributes(innerHit.source().variantAttributes())
                        .build());
                }
            }
        }

        return ProductSearchItem.builder()
            .productId(doc.productId())
            .skuId(doc.skuId())
            .name(doc.name())
            .thumbnailUrl(doc.thumbnailUrl())
            .originalPrice(doc.originalPrice())
            .bestPrice(doc.bestPrice())
            .discountPercentage(discountPct)
            .productRatingAvg(doc.productRatingAvg())
            .isInStock(doc.isInStock())
            .matchingSkus(matchingSkus)
            .build();
    }

    private FacetResponse extractFacets(Map<String, ?> aggregations) {
        if (aggregations == null || aggregations.isEmpty()) {
            return null;
        }
        // Implementation: parse ES aggregations → FacetResponse
        // (categories, price_ranges, brands)
        // ...
        return null; // TODO detailed implementation
    }
}
```

> **Lưu ý:** `extractFacets()` cần parse ES aggregation results → `FacetResponse`. Implementation chi tiết parsing aggregations từ Map<String, Aggregate> sang DTO.

---

## 3. SuggestService

### 3.1 File: `service/SuggestService.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/SuggestService.java`

- [ ] Create `@Service` class
- [ ] Method: `SuggestResponse suggest(String q, int limit)`
- [ ] Validate `q` có ít nhất 2 ký tự (throw `INVALID_QUERY_PARAMETER`)
- [ ] Build query via `SuggestQueryBuilder`
- [ ] Transform hits → `SuggestResponse.SuggestItem`

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.SuggestResponse;
import com.ecommerce.search.repository.SuggestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestService {

    private static final int MAX_LIMIT = 20;
    private static final int MIN_QUERY_LENGTH = 2;

    private final SuggestRepository repository;
    private final SuggestQueryBuilder queryBuilder;
    private final SearchProperties properties;

    public SuggestResponse suggest(String q, Integer limit) {
        // 1. Validate
        if (q == null || q.trim().length() < MIN_QUERY_LENGTH) {
            throw new SearchException(ErrorCode.INVALID_QUERY_PARAMETER,
                "Query 'q' phải có ít nhất " + MIN_QUERY_LENGTH + " ký tự");
        }

        int actualLimit = limit == null ? 8 : Math.min(limit, MAX_LIMIT);

        // 2. Build + execute
        SearchRequest request = queryBuilder.build(q.trim(), actualLimit);
        SearchResponse<SuggestionDocument> response = repository.search(request);

        // 3. Transform
        List<SuggestResponse.SuggestItem> items = response.hits().hits().stream()
            .map(hit -> hit.source())
            .filter(java.util.Objects::nonNull)
            .map(doc -> new SuggestResponse.SuggestItem(doc.text(), doc.type(), doc.weight()))
            .toList();

        return new SuggestResponse(items);
    }
}
```

---

## 4. CategoryService

### 4.1 File: `service/CategoryService.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/CategoryService.java`

- [ ] Method: `CategoryTreeResponse getCategoryTree()`
- [ ] Build query via `CategoryQueryBuilder`
- [ ] Transform flat list → tree structure (app-level)

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.dto.CategoryTreeResponse;
import com.ecommerce.search.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repository;
    private final CategoryQueryBuilder queryBuilder;

    public CategoryTreeResponse getCategoryTree() {
        SearchRequest request = queryBuilder.build();
        SearchResponse<CategoryDocument> response = repository.search(request);

        // Return flat list (frontend builds tree UI)
        List<CategoryTreeResponse.CategoryNode> nodes = response.hits().hits().stream()
            .map(Hit::source)
            .filter(java.util.Objects::nonNull)
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

        return new CategoryTreeResponse(nodes);
    }
}
```

---

## 5. FacetService

### 5.1 File: `service/FacetService.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/FacetService.java`

- [ ] Method: `FacetResponse getFacets(String categoryId)`
- [ ] Validate `categoryId` exists (optional: query trước)
- [ ] Build query via `FacetQueryBuilder`
- [ ] Transform aggregations → `FacetResponse`

---

## 6. ProductSearchController

### 6.1 File: `controller/public/ProductSearchController.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/controller/public/ProductSearchController.java`

- [ ] Create `@RestController` với `@RequestMapping("/api/v1/search/products")`
- [ ] Method: `GET /` — endpoint chính
- [ ] Accept `SearchParams` (parse từ query string)
- [ ] Auto-populate MDC context từ JWT claims (via `JwtClaimUtil.populateMdcContext()`)
- [ ] Delegate tới `ProductSearchService`
- [ ] Return `ResponseEntity<ProductSearchResponse>`

```java
package com.ecommerce.search.controller.public;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.dto.ProductSearchResponse;
import com.ecommerce.search.service.ProductSearchService;
import com.ecommerce.search.service.SearchParams;
import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService service;
    private final SearchProperties properties;
    private final JwtClaimUtil jwtClaimUtil;

    @GetMapping("/products")
    public ResponseEntity<ProductSearchResponse> search(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) Map<String, List<String>> filters,
        @RequestParam(required = false) Long priceMin,
        @RequestParam(required = false) Long priceMax,
        @RequestParam(required = false) Boolean inStock,
        @RequestParam(required = false) Boolean hasPromotion,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer size,
        HttpServletRequest httpRequest
    ) {
        // 1. Auto-populate MDC context for structured logging
        jwtClaimUtil.populateMdcContext(httpRequest);

        // 2. Build params (validation in SearchParams.of)
        SearchParams params = SearchParams.of(q, category, filters, priceMin, priceMax,
            inStock, hasPromotion, sort, cursor, size, properties);

        // 3. Execute search
        ProductSearchResponse response = service.search(params);

        return ResponseEntity.ok(response);
    }
}
```

### 6.2 File: `controller/public/SuggestController.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/controller/public/SuggestController.java`

- [ ] `@RestController` với `@RequestMapping("/api/v1/search/suggest")`
- [ ] GET endpoint với `q` (required) + `limit` (optional, default 8, max 20)

### 6.3 File: `controller/public/CategoryController.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/controller/public/CategoryController.java`

- [ ] `@RestController` với `@RequestMapping("/api/v1/search/categories")`
- [ ] GET endpoint trả `CategoryTreeResponse`

### 6.4 File: `controller/public/FacetController.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/controller/public/FacetController.java`

- [ ] `@RestController` với `@RequestMapping("/api/v1/search/facets")`
- [ ] GET `/{categoryId}` endpoint

---

## 7. Cross-Cutting Concerns Filter (MDC Population)

### 7.1 File: `config/MdcPopulationFilter.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/MdcPopulationFilter.java`

- [ ] Create `OncePerRequestFilter` cho mọi request
- [ ] Auto-populate MDC `request_id` + `user_id` từ `JwtClaimUtil`
- [ ] Clear MDC sau khi response (tránh leak)
- [ ] **Skip filter cho Actuator endpoints** (health checks)

```java
package com.ecommerce.search.config;

import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MdcPopulationFilter extends OncePerRequestFilter {

    private final JwtClaimUtil jwtClaimUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            // Skip actuator endpoints
            if (req.getRequestURI().startsWith("/actuator")) {
                chain.doFilter(req, res);
                return;
            }
            jwtClaimUtil.populateMdcContext(req);
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

---

## 8. Acceptance Criteria

- [ ] `GET /api/v1/search/products?q=iphone` returns 200 + search results
- [ ] Cursor pagination: `next_cursor` returned khi còn nhiều kết quả, `null` khi hết
- [ ] `discount_percentage` tính đúng (e.g., 100→80 = 20%)
- [ ] `GET /api/v1/search/suggest?q=iph` returns 200 + suggestions
- [ ] Validation: `q` < 2 ký tự → 400 `INVALID_QUERY_PARAMETER`
- [ ] `GET /api/v1/search/categories` returns flat list
- [ ] `GET /api/v1/search/facets/{id}` returns aggregations
- [ ] All responses theo DTO format (snake_case JSON)
- [ ] MDC context populated với `request_id` + `user_id` cho mọi request
- [ ] Timeout 5s cho product search, 2s cho suggest
- [ ] Tests với Testcontainers verify end-to-end flow

---

## 9. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `service/ProductSearchService.java` | ⏳ Not yet |
| 2 | `service/SuggestService.java` | ⏳ Not yet |
| 3 | `service/CategoryService.java` | ⏳ Not yet |
| 4 | `service/FacetService.java` | ⏳ Not yet |
| 5 | `controller/public/ProductSearchController.java` | ⏳ Not yet |
| 6 | `controller/public/SuggestController.java` | ⏳ Not yet |
| 7 | `controller/public/CategoryController.java` | ⏳ Not yet |
| 8 | `controller/public/FacetController.java` | ⏳ Not yet |
| 9 | `config/MdcPopulationFilter.java` | ⏳ Not yet |
| 10 | `service/ProductSearchServiceTest.java` | ⏳ Not yet |
| 11 | `service/SuggestServiceTest.java` | ⏳ Not yet |
| 12 | `controller/public/ProductSearchControllerTest.java` | ⏳ Not yet |
| 13 | `controller/public/ProductSearchControllerIT.java` (Testcontainers) | ⏳ Not yet |
