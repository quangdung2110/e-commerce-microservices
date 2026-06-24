# Module 04: Search Query Builders

> **Status:** Module 4 of 14 - **✅ COMPLETED** (June 23, 2026)
> **Prerequisites:** Module 03 (Elasticsearch Config)
> **Dependents:** Module 05 (Public API Controllers)
> **Estimated Effort:** 2 person-days

> ## Implementation Note
> ✅ **Implemented June 23, 2026.** All files compiled successfully with zero errors.
>
> **Key design decisions:**
> - Dynamic attribute filters use `bool/should` checking both `searchable_attributes` and `variant_attributes` — no hardcoded attribute keys, works with any category.
> - Facet aggregations return raw `key:value` tokens from both attribute fields; frontend groups by prefix to render dynamic filter groups.
> - `SortMapper` always appends `sku_id asc` tie-breaker for stable cursor pagination.

---

## 1. Overview

This module builds Elasticsearch query DSL theo [Search Workflow §2](../search_workflow.md) và [Elasticsearch Schema](../search_elasticsearch_schema.md):
- **ProductQueryBuilder:** Build query cho `/api/v1/search/products` (complex bool query + collapse + aggs)
- **SuggestQueryBuilder:** Build query cho `/api/v1/search/suggest` (match query + sort)
- **CategoryQueryBuilder:** Build query cho `/api/v1/search/categories` (size 1000 + sort)
- **FacetQueryBuilder:** Build aggregation query cho `/api/v1/search/facets/{id}`
- **CursorCodec:** Encode/decode cursor (base64 JSON) — moved to Module 06
- **DiscountCalculator:** Helper để tính `discount_percentage` chuẩn
- **SortMapper:** Map `sort` query param → ES sort clause

Tất cả builders sử dụng **ES Java API Client 8.x fluent builders** (type-safe).

---

## 2. Sort Mapper

### 2.1 File: `service/SortMapper.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/SortMapper.java`

- [ ] Create `@Component` class
- [ ] Map query string `sort` → ES `SortOptions` list
- [ ] Mọi sort đều append tie-break `sku_id asc` (đảm bảo pagination ổn định)
- [ ] Default sort: `relevance` (`_score desc`)

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SortMapper {

    public static final String SORT_RELEVANCE = "relevance";
    public static final String SORT_PRICE_ASC = "price_asc";
    public static final String SORT_PRICE_DESC = "price_desc";
    public static final String SORT_NEWEST = "newest";
    public static final String SORT_RATING = "rating";

    public List<SortOptions> map(String sortParam) {
        String sort = sortParam == null ? SORT_RELEVANCE : sortParam;
        List<SortOptions> sortOptions = switch (sort) {
            case SORT_PRICE_ASC -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Asc))),
                tieBreakSkuId()
            );
            case SORT_PRICE_DESC -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            case SORT_NEWEST -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("updated_at").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            case SORT_RATING -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("product_rating_avg").order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("product_rating_count").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            default -> List.of(
                SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))),
                tieBreakSkuId()
            );
        };
        return sortOptions;
    }

    private SortOptions tieBreakSkuId() {
        return SortOptions.of(s -> s.field(f -> f.field("sku_id").order(SortOrder.Asc)));
    }
}
```

> **Tại sao cần tie-break `sku_id asc`:** Pagination ổn định khi dùng `search_after` (xem [Search Workflow §2](../search_workflow.md#2-api-tìm-kiếm-sản-phẩm-full-text-search--filters--facets)).

---

## 3. Discount Calculator

### 3.1 File: `service/DiscountCalculator.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/DiscountCalculator.java`

- [ ] Create utility class (static methods)
- [ ] Method: `int calculateDiscountPercentage(Long originalPrice, Long bestPrice)` (theo [Search Workflow §2 bước 3](../search_workflow.md))

```java
package com.ecommerce.search.service;

public final class DiscountCalculator {
    private DiscountCalculator() {}

    /**
     * discount_percentage = floor(((original_price - best_price) / original_price) * 100)
     * Return 0 if original_price <= 0 or best_price >= original_price.
     */
    public static int calculate(Long originalPrice, Long bestPrice) {
        if (originalPrice == null || bestPrice == null || originalPrice <= 0) return 0;
        if (bestPrice >= originalPrice) return 0;
        return (int) Math.floor(((double) (originalPrice - bestPrice) / originalPrice) * 100);
    }
}
```

---

## 4. Product Query Builder

### 4.1 File: `service/ProductQueryBuilder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/ProductQueryBuilder.java`

- [ ] Create `@Component` class
- [ ] Inject `SortMapper`
- [ ] Build complete SearchRequest cho `/api/v1/search/products`
- [ ] Method: `SearchRequest build(SearchParams params, List<Object> searchAfter)`
- [ ] Method: `Query buildBoolQuery(SearchParams params)` — build the bool query với must + filter
- [ ] Method: `List<Aggregation> buildAggregations()` — categories + price_ranges

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.FieldCollapse;
import co.elastic.clients.elasticsearch.core.search.InnerHit;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ProductQueryBuilder {

    private final SortMapper sortMapper;
    private final SearchProperties properties;

    public SearchRequest build(SearchParams params, List<FieldValue> searchAfter) {
        try {
            Query boolQuery = buildBoolQuery(params);
            List<SortOptions> sortOptions = sortMapper.map(params.sort());

            SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(properties.getEs().getIndexAliases().getProducts())
                .query(boolQuery)
                .collapse(buildCollapse())
                .sort(sortOptions)
                .size(params.size())
                .trackTotalHits(t -> t.enabled(true));

            if (searchAfter != null && !searchAfter.isEmpty()) {
                builder.searchAfter(searchAfter);
            }

            builder.aggregations(buildAggregations());

            return builder.build();
        } catch (Exception e) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Failed to build query", e);
        }
    }

    private Query buildBoolQuery(SearchParams params) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        // Multi-match on text fields
        if (params.q() != null && !params.q().isBlank()) {
            must.add(Query.of(q -> q.multiMatch(mm -> mm
                .query(params.q())
                .fields("name^3", "name.keyword^2", "searchable_attributes")
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")
            )));
        } else {
            must.add(Query.of(q -> q.matchAll(m -> m)));
        }

        // Standard filters
        filter.add(Query.of(q -> q.term(t -> t.field("is_deleted").value(false))));
        filter.add(Query.of(q -> q.term(t -> t.field("product_status").value("ACTIVE"))));
        filter.add(Query.of(q -> q.term(t -> t.field("sku_status").value("ACTIVE"))));

        if (Boolean.TRUE.equals(params.inStock())) {
            filter.add(Query.of(q -> q.term(t -> t.field("is_in_stock").value(true))));
        }

        if (params.category() != null) {
            filter.add(Query.of(q -> q.term(t -> t.field("category_id").value(params.category()))));
        }

        if (params.priceMin() != null || params.priceMax() != null) {
            filter.add(Query.of(q -> q.range(r -> {
                r.field("best_price");
                if (params.priceMin() != null) r.gte(JsonData.of(params.priceMin()));
                if (params.priceMax() != null) r.lte(JsonData.of(params.priceMax()));
                return r;
            })));
        }

        if (Boolean.TRUE.equals(params.hasPromotion())) {
            filter.add(Query.of(q -> q.term(t -> t.field("has_promotion").value(true))));
        }

        // Dynamic filters — check cả searchable_attributes AND variant_attributes
        // Dùng bool/should vì không biết trước attribute là product-level hay SKU-level.
        // Format key:value giống nhau ở cả 2 field ("thuonghieu:apple", "mausac:đen").
        // Với mỗi filter value, tạo 1 bool query: minimumShouldMatch=1 → match nếu tồn tại ở 1 trong 2 field.
        if (params.filters() != null) {
            params.filters().forEach((key, values) -> {
                values.forEach(v -> {
                    String termValue = key + ":" + v;
                    filter.add(Query.of(q -> q.bool(b -> b
                        .minimumShouldMatch("1")
                        .should(s -> s.term(t -> t.field("searchable_attributes").value(termValue)))
                        .should(s -> s.term(t -> t.field("variant_attributes").value(termValue)))
                    )));
                });
            });
        }

        return Query.of(q -> q.bool(b -> b.must(must).filter(filter)));
    }

    private FieldCollapse buildCollapse() {
        return FieldCollapse.of(fc -> fc
            .field("product_id")
            .innerHits(InnerHit.of(ih -> ih
                .name("matching_skus")
                .size(3)
                .sort(List.of(
                    SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Asc))),
                    SortOptions.of(s -> s.field(f -> f.field("sku_id").order(SortOrder.Asc)))
                ))
            ))
        );
    }

    private Map<String, Aggregation> buildAggregations() {
        Map<String, Aggregation> aggs = new HashMap<>();

        aggs.put("categories", Aggregation.of(a -> a
            .terms(t -> t.field("category_id").size(20))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        aggs.put("price_ranges", Aggregation.of(a -> a
            .range(r -> r.field("best_price").ranges(List.of(
                RangeBucket.of(b -> b.to(JsonData.of(5000000.0)).key("Dưới 5 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(5000000.0)).to(JsonData.of(15000000.0)).key("5 - 15 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(15000000.0)).to(JsonData.of(30000000.0)).key("15 - 30 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(30000000.0)).key("Trên 30 triệu"))
            )))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        return aggs;
    }
}
```

### 4.2 File: `service/SearchParams.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/SearchParams.java`

- [ ] Create `SearchParams` Java record (validated input)
- [ ] Fields: `q`, `category`, `filters` (Map<String, List<String>>), `priceMin`, `priceMax`, `inStock`, `hasPromotion`, `sort`, `cursor`, `size`

```java
package com.ecommerce.search.service;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.common.ErrorCode;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

public record SearchParams(
    String q,
    String category,
    Map<String, List<String>> filters,
    @Min(0) Long priceMin,
    @Min(0) Long priceMax,
    Boolean inStock,
    Boolean hasPromotion,
    @Pattern(regexp = "relevance|price_asc|price_desc|newest|rating") String sort,
    String cursor,
    @Min(1) int size
) {
    public SearchParams {
        if (size <= 0) size = 1;
    }

    public static SearchParams of(String q, String category, Map<String, List<String>> filters,
                                   Long priceMin, Long priceMax, Boolean inStock, Boolean hasPromotion,
                                   String sort, String cursor, Integer size, SearchProperties properties) {
        int defaultSize = properties.getPagination().getDefaultSize();
        int maxSize = properties.getPagination().getMaxSize();
        int actualSize = size == null ? defaultSize : Math.min(size, maxSize);

        if (sort == null) sort = "relevance";
        return new SearchParams(q, category, filters, priceMin, priceMax,
            inStock == null ? Boolean.TRUE : inStock,
            hasPromotion, sort, cursor, actualSize);
    }
}
```

---

## 5. Suggest Query Builder

### 5.1 File: `service/SuggestQueryBuilder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/SuggestQueryBuilder.java`

- [ ] Create `@Component` class
- [ ] Method: `SearchRequest build(String q, int limit)`
- [ ] Sort theo `weight desc` + tie-break `text.keyword asc`
- [ ] Source filter: chỉ trả `text`, `type`, `weight`

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.search.SearchSourceConfig;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SuggestQueryBuilder {

    private final SearchProperties properties;

    public SearchRequest build(String q, int limit) {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getSuggestions())
            .query(Query.of(qb -> qb.match(m -> m.field("text").query(q))))
            .sort(List.of(
                SortOptions.of(so -> so.field(f -> f.field("weight").order(SortOrder.Desc))),
                SortOptions.of(so -> so.field(f -> f.field("text.keyword").order(SortOrder.Asc)))
            ))
            .size(limit)
            .source(src -> src.filter(f -> f.includes("text", "type", "weight")))
        );
    }
}
```

---

## 6. Category Query Builder

### 6.1 File: `service/CategoryQueryBuilder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/CategoryQueryBuilder.java`

- [ ] Method: `SearchRequest build()` — size 1000, sort by `level asc, category_id asc`

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CategoryQueryBuilder {

    private final SearchProperties properties;

    public SearchRequest build() {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getCategories())
            .size(1000)
            .sort(List.of(
                SortOptions.of(so -> so.field(f -> f.field("level").order(SortOrder.Asc))),
                SortOptions.of(so -> so.field(f -> f.field("category_id").order(SortOrder.Asc)))
            ))
        );
    }
}
```

---

## 7. Facet Query Builder

### 7.1 File: `service/FacetQueryBuilder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/FacetQueryBuilder.java`

- [ ] Method: `SearchRequest build(String categoryId)` — aggregations cho brands, price_ranges, dynamic attributes
- [ ] All aggregations dùng `cardinality` trên `product_id` để đếm DISTINCT product

```java
package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FacetQueryBuilder {

    private final SearchProperties properties;

    public SearchRequest build(String categoryId) {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getProducts())
            .size(0)  // chỉ cần aggregations
            .query(Query.of(q -> q.bool(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))),
                    Query.of(qf -> qf.term(t -> t.field("product_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("category_id").value(categoryId)))
                )
            )))
            .aggregations(buildAggregations())
        );
    }

    private Map<String, Aggregation> buildAggregations() {
        Map<String, Aggregation> aggs = new HashMap<>();

        // Dynamic attribute facets — aggregation trên cả 2 field để frontend render động
        // Mỗi token là "key:value" (VD: "thuonghieu:apple", "mausac:đen").
        // Frontend nhận về, nhóm theo prefix (key trước dấu :) để hiển thị bộ lọc.
        // Cần cardinality product_count vì index là SKU-first (đếm DISTINCT product).
        aggs.put("searchable_attributes", Aggregation.of(a -> a
            .terms(t -> t.field("searchable_attributes").size(50))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        aggs.put("variant_attributes", Aggregation.of(a -> a
            .terms(t -> t.field("variant_attributes").size(50))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        // Price ranges (giống search API)
        aggs.put("price_ranges", Aggregation.of(a -> a
            .range(r -> r.field("best_price").ranges(List.of(
                RangeBucket.of(b -> b.to(JsonData.of(5000000.0)).key("Dưới 5 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(5000000.0)).to(JsonData.of(15000000.0)).key("5 - 15 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(15000000.0)).to(JsonData.of(30000000.0)).key("15 - 30 triệu")),
                RangeBucket.of(b -> b.from(JsonData.of(30000000.0)).key("Trên 30 triệu"))
            )))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        return aggs;
    }
}
```

---

## 8. Acceptance Criteria

- [x] `ProductQueryBuilder.build()` produces valid ES query JSON
- [x] `SortMapper.map("price_asc")` returns `[best_price asc, sku_id asc]`
- [x] `DiscountCalculator.calculate(100, 80)` returns `20` (20% discount)
- [x] `SuggestQueryBuilder.build("iph", 8)` produces match query với size 8
- [x] `CategoryQueryBuilder.build()` returns size 1000
- [x] `FacetQueryBuilder.build(catId)` returns aggregations với cardinality on `product_id`
- [x] `search_after` cursor format: `[sort_value, sku_id]` array
- [x] Tie-break `sku_id asc` luôn ở cuối mỗi sort
- [x] Unit test với mocked ES client verify đúng query structure
- [x] Dynamic filters dùng `bool/should` — không hardcode attribute keys

---

## 9. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `service/SortMapper.java` | ✅ Completed |
| 2 | `service/DiscountCalculator.java` | ✅ Completed |
| 3 | `service/ProductQueryBuilder.java` | ✅ Completed |
| 4 | `service/SearchParams.java` | ✅ Completed |
| 5 | `service/SuggestQueryBuilder.java` | ✅ Completed |
| 6 | `service/CategoryQueryBuilder.java` | ✅ Completed |
| 7 | `service/FacetQueryBuilder.java` | ✅ Completed |
| 8 | `service/SortMapperTest.java` | ✅ Completed |
| 9 | `service/DiscountCalculatorTest.java` | ✅ Completed |
| 10 | `service/ProductQueryBuilderTest.java` | ✅ Completed |
| 11 | `service/SuggestQueryBuilderTest.java` | ✅ Completed |
| 12 | `service/CategoryQueryBuilderTest.java` | ✅ Completed |
| 13 | `service/FacetQueryBuilderTest.java` | ✅ Completed |
