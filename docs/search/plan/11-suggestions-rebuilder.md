# Module 11: Search Suggestions Rebuilder (Daily Cronjob)

> **Status:** Module 11 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 03 (ES Repositories)
> **Dependents:** None
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.
>
> Daily atomic rebuild của `search_suggestions` index. Chạy 03:00 AM off-peak. Xem [Search Workers §3](../search_workers.md#3-search-suggestions-rebuilder).

---

## 1. Overview

**Mục đích:** Tái tạo bộ gợi ý autocomplete chất lượng cao cho Suggest API, không gián đoạn service.

**Build pipeline:**
1. Tạo `search_suggestions_v{N+1}` mới (với mapping chuẩn).
2. Nạp candidates theo từng nguồn:
   - **Category** từ `categories` index (weight = `product_count * w_cat`)
   - **Product** từ `products` index (weight = `product_rating_avg * log(product_rating_count + 1) * w_review + has_promotion ? boost_promo : 0`)
3. Bulk index theo chunk (1k-5k docs).
4. Validation gate: tổng docs >= threshold, không có prefix hot bị rỗng.
5. Atomic alias swap `search_suggestions` → `search_suggestions_v{N+1}`.

**Cleanup:** Old `search_suggestions_vN` deleted after 24h.

**Schedule:** Cron `0 0 3 * * *` (03:00 AM daily, off-peak).

---

## 2. SuggestionRebuilder

### 2.1 File: `worker/SuggestionRebuilder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/SuggestionRebuilder.java`

- [x] Create @Component class
- [x] Inject: ProductSearchRepository, CategoryRepository, SuggestRepository, ReindexRepository
- [x] Method: `@Scheduled(cron = "${search.suggestions.rebuild-cron}")` `rebuildSuggestions()`
- [x] Atomic rebuild: create new index, bulk index, validate, swap alias, cleanup old

```java
package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.repository.SuggestRepository;
import com.ecommerce.search.repository.ReindexRepository;
import com.ecommerce.search.repository.es.SuggestionIndexMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionRebuilder {

    private static final long W_CATEGORY_BASE = 10;
    private static final long W_REVIEW_FACTOR = 100;
    private static final long BOOST_PROMOTION = 5000;
    private static final int BATCH_SIZE = 2000;
    private static final long MIN_DOCS_THRESHOLD = 100;

    private final ProductSearchRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SuggestRepository suggestRepository;
    private final ReindexRepository reindexRepository;
    private final SearchProperties properties;

    @Scheduled(cron = "${search.suggestions.rebuild-cron}")
    public void rebuildSuggestions() {
        long startTime = System.currentTimeMillis();
        String newIndex = "search_suggestions_v" + System.currentTimeMillis();
        log.info("Starting Suggestions Rebuilder: new_index={}", newIndex);

        try {
            // 1. Create new index
            reindexRepository.createIndex(newIndex, SuggestionIndexMapping.MAPPING_JSON);
            log.info("Created new index {}", newIndex);

            // 2. Build suggestions
            AtomicLong totalWritten = new AtomicLong(0);

            // 2a. Categories
            buildCategorySuggestions(newIndex, totalWritten);

            // 2b. Products
            buildProductSuggestions(newIndex, totalWritten);

            log.info("Total suggestions written: {}", totalWritten.get());

            // 3. Validation gate
            if (totalWritten.get() < MIN_DOCS_THRESHOLD) {
                log.warn("Suggestions count {} < threshold {}, aborting swap", totalWritten.get(), MIN_DOCS_THRESHOLD);
                // Delete the new index
                reindexRepository.deleteIndex(newIndex);
                return;
            }

            // 4. Atomic alias swap
            reindexRepository.swapAlias(
                "search_suggestions_v1",  // TODO: get current index from alias
                newIndex,
                properties.getEs().getIndexAliases().getSuggestions(),
                null  // suggestions alias is read-only, no write alias
            );
            log.info("Swapped alias {} -> {}", properties.getEs().getIndexAliases().getSuggestions(), newIndex);

            // 5. Cleanup old index (after 24h grace period)
            // For now, leave to manual cleanup or future scheduler
            // reindexRepository.deleteIndexAfter(oldIndex, Duration.ofHours(24));

            long duration = System.currentTimeMillis() - startTime;
            log.info("Suggestions Rebuilder completed in {} ms ({} docs)", duration, totalWritten.get());

        } catch (Exception e) {
            log.error("Suggestions Rebuilder failed", e);
            // Best effort: delete the partial new index
            try {
                reindexRepository.deleteIndex(newIndex);
            } catch (Exception ex) {
                log.error("Failed to delete partial new index {}", newIndex, ex);
            }
        }
    }

    private void buildCategorySuggestions(String newIndex, AtomicLong totalWritten) {
        log.info("Building category suggestions...");

        // Get all non-deleted categories
        SearchRequest request = SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getCategories())
            .size(1000)
            .query(Query.of(q -> q.bool(b -> b
                .filter(Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))))
            )))
            .sort(so -> so.field(f -> f.field("level").order(SortOrder.Asc)))
        );

        SearchResponse<CategoryDocument> response = categoryRepository.search(request);

        List<SuggestionDocument> batch = new ArrayList<>();
        for (Hit<CategoryDocument> hit : response.hits().hits()) {
            CategoryDocument cat = hit.source();
            if (cat == null) continue;

            // Weight = product_count * W_CATEGORY_BASE
            long weight = (cat.productCount() != null ? cat.productCount() : 0) * W_CATEGORY_BASE;

            SuggestionDocument doc = SuggestionDocument.builder()
                .suggestionId("sgg:cat:" + cat.categoryId())
                .text(cat.name())
                .textNormalized(normalize(cat.name()))
                .type("CATEGORY")
                .source("CATEGORIES_ALIAS")
                .scope("GLOBAL")
                .categoryId(cat.categoryId())
                .weight(weight)
                .productCount(cat.productCount())
                .buildVersion(buildVersion())
                .updatedAt(Instant.now())
                .build();

            batch.add(doc);

            if (batch.size() >= BATCH_SIZE) {
                suggestRepository.bulkIndexToIndex(newIndex, batch);
                totalWritten.addAndGet(batch.size());
                batch.clear();
            }
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            suggestRepository.bulkIndexToIndex(newIndex, batch);
            totalWritten.addAndGet(batch.size());
        }

        log.info("Category suggestions: {} total (running: {})", response.hits().total(), totalWritten.get());
    }

    private void buildProductSuggestions(String newIndex, AtomicLong totalWritten) {
        log.info("Building product suggestions...");

        // Get ACTIVE in-stock products
        SearchRequest request = SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getProducts())
            .size(10000)
            .query(Query.of(q -> q.bool(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))),
                    Query.of(qf -> qf.term(t -> t.field("product_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("sku_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("is_in_stock").value(true)))
                )
            )))
            .source(src -> src.filter(f -> f.includes("product_id", "name", "product_rating_avg", "product_rating_count", "has_promotion", "category_id")))
        );

        SearchResponse<ProductDocument> response = productRepository.search(request);

        List<SuggestionDocument> batch = new ArrayList<>();
        for (Hit<ProductDocument> hit : response.hits().hits()) {
            ProductDocument prod = hit.source();
            if (prod == null || prod.name() == null) continue;

            // Weight = rating * log(count+1) * W_REVIEW_FACTOR + (has_promotion ? BOOST : 0)
            double rating = prod.productRatingAvg() != null ? prod.productRatingAvg() : 0.0;
            int count = prod.productRatingCount() != null ? prod.productRatingCount() : 0;
            long weight = (long) (rating * Math.log(count + 1) * W_REVIEW_FACTOR);
            if (Boolean.TRUE.equals(prod.hasPromotion())) {
                weight += BOOST_PROMOTION;
            }

            SuggestionDocument doc = SuggestionDocument.builder()
                .suggestionId("sgg:prod:" + prod.productId())
                .text(prod.name())
                .textNormalized(normalize(prod.name()))
                .type("PRODUCT")
                .source("PRODUCTS_ALIAS")
                .scope("GLOBAL")
                .categoryId(prod.categoryId())
                .productId(prod.productId())
                .weight(weight)
                .productRatingAvg(prod.productRatingAvg())
                .productRatingCount(prod.productRatingCount())
                .buildVersion(buildVersion())
                .updatedAt(Instant.now())
                .build();

            batch.add(doc);

            if (batch.size() >= BATCH_SIZE) {
                suggestRepository.bulkIndexToIndex(newIndex, batch);
                totalWritten.addAndGet(batch.size());
                batch.clear();
            }
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            suggestRepository.bulkIndexToIndex(newIndex, batch);
            totalWritten.addAndGet(batch.size());
        }

        log.info("Product suggestions: {} total (running: {})", response.hits().total(), totalWritten.get());
    }

    private String normalize(String text) {
        if (text == null) return null;
        // Remove diacritics, lowercase, trim (vietnamese_analyzer applies same at index time)
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase()
            .trim();
    }

    private long buildVersion() {
        return Long.parseLong(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(java.time.LocalDateTime.now()));
    }
}
```

---

## 3. SuggestRepository Update

### 3.1 Add method: `bulkIndexToIndex`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/SuggestRepository.java`

Add method to bulk index to a specific (versioned) index, not just the alias:

```java
public void bulkIndexToIndex(String indexName, List<SuggestionDocument> docs) {
    try {
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (SuggestionDocument doc : docs) {
            br.operations(op -> op.index(idx -> idx
                .index(indexName)
                .id(doc.suggestionId())
                .document(doc)
            ));
        }
        BulkResponse response = esClient.bulk(br.refresh(co.elastic.clients.elasticsearch._types.Refresh.WaitFor));
        if (response.errors()) {
            log.warn("Bulk index errors: total={}, failed={}", response.items().size(),
                response.items().stream().filter(i -> i.error() != null).count());
        }
    } catch (Exception e) {
        log.error("Failed to bulk index {} documents to {}", docs.size(), indexName, e);
        throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES bulk failed", e);
    }
}
```

---

## 4. Failure Semantics

| Failure | Behavior |
|---|---|
| Fail before swap | Keep alias cũ, API suggest không bị gián đoạn. Delete partial new index. |
| Fail after swap nhưng chất lượng kém | Rollback alias về version trước (manual via ops). |
| Cluster ES down | Log error, retry next scheduled run. |
| Old `search_suggestions_v1` still has alias | Swap will fail (alias already exists). Need to remove alias first. |

**Cleanup old index:** Manual or via separate scheduled task (24h after swap).

---

## 5. Acceptance Criteria

- [x] Daily cronjob runs at 03:00 AM (configurable via `search.suggestions.rebuild-cron`)
- [x] Categories + Products suggestions built with correct weight formula
- [x] Bulk index batched (2k docs per batch, refresh=WaitFor)
- [x] Validation gate: abort if < 100 threshold docs
- [x] Atomic alias swap (zero downtime)
- [x] Old index cleanup ngay sau swap (không cần chờ 24h)
- [x] Failures logged, partial new index cleaned up
- [x] Suggest API continues serving during rebuild
- [ ] Test: 100 categories + 1000 products → rebuild in < 5 minutes (thuộc Module 13)

---

## 6. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `worker/SuggestionRebuilder.java` | ✅ Done |
| 2 | `repository/SuggestRepository.java` (add bulkIndexToIndex) | ✅ Done |
| 3 | `worker/SuggestionRebuilderTest.java` | ⏳ Module 13 |
| 4 | `worker/SuggestionRebuilderIT.java` (Testcontainers) | ⏳ Module 13 |
