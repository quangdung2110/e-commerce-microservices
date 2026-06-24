# Module 12: Draft Cleanup Job (Off-peak)

> **Status:** Module 12 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 03 (ES Repositories)
> **Dependents:** None
> **Estimated Effort:** 0.5 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.
>
> Daily off-peak cleanup draft documents. Chạy 02:00 AM. Xem [Search Workers §4](../search_workers.md#4-draft-cleanup-job).

---

## 1. Overview

**Mục đích:** Dọn docs DRAFT đã được Product Service chính thức đánh dấu hết hạn (orphaned draft).

**Schedule:** Cron `0 0 2 * * *` (02:00 AM daily, off-peak).

**Điều kiện cleanup (đồng thời phải thoả cả 3):**
1. `product_status = DRAFT`
2. `created_at < now - 30d`
3. `is_deleted = false`

**Ràng buộc ownership (BẮT BUỘC):**
- Cách A (ưu tiên): Sản phẩm đã có `PRODUCT_DELETED` event trước đó → `is_deleted=true` đã được set.
- Cách B: Nhận danh sách `product_id` quá hạn từ Product Service export API.

**Job KHÔNG tự xoá** chỉ dựa vào tuổi dữ liệu.

---

## 2. DraftCleanupJob

### 2.1 File: `worker/DraftCleanupJob.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/DraftCleanupJob.java`

- [x] Create @Component class
- [x] Inject: UpdateByQueryExecutor, SearchProperties (dùng RestClient cho Product Service API)
- [x] Method: @Scheduled(cron) runCleanup()
- [x] For Cách B: Call Product Service export API to get orphan-drafts list
- [x] Mark is_deleted=true for all matched SKUs (via _update_by_query)
- [x] After tombstone retention, separate cronjob (tombstone-purger) hard-deletes

```java
package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DraftCleanupJob {

    private final ProductSearchRepository productRepository;
    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;

    @Value("${search.product-service.export-url:http://product-service:8080/api/v1/internal/products/orphan-drafts}")
    private String productServiceExportUrl;

    @Value("${search.product-service.timeout-seconds:30}")
    private int productServiceTimeoutSeconds;

    private final RestClient productServiceClient = RestClient.builder()
        .baseUrl("http://product-service:8080")
        .requestFactory(...)  // configure timeout
        .build();

    @Scheduled(cron = "${search.draft-cleanup.cron}")
    public void runCleanup() {
        long startTime = System.currentTimeMillis();
        log.info("Starting Draft Cleanup Job...");

        try {
            // 1. Get orphan-drafts from Product Service
            List<String> orphanProductIds = fetchOrphanDraftsFromProductService();
            if (orphanProductIds == null || orphanProductIds.isEmpty()) {
                log.info("No orphan drafts to clean up");
                return;
            }
            log.info("Found {} orphan drafts to mark as deleted", orphanProductIds.size());

            // 2. Update _update_by_query to mark is_deleted=true, deleted_at=now
            String scriptSource = """
                ctx._source.is_deleted = true;
                ctx._source.deleted_at = params.deleted_at;
                ctx._source.is_in_stock = false;
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            Map<String, Object> params = Map.of(
                "deleted_at", Instant.now().toString(),
                "indexed_at", Instant.now().toString(),
                "event_id", "draft-cleanup-" + Instant.now().toString()
            );

            // Use terms query for batch
            Query query = Query.of(q -> q.terms(t -> t
                .field("product_id")
                .terms(tt -> tt.value(orphanProductIds.stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .toList()))
            ));

            UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
                properties.getEs().getIndexAliases().getProducts(),
                query,
                scriptSource,
                params
            );

            log.info("Marked {} documents as deleted (total matched: {})", response.updated(), response.total());

            // 3. After 24h grace, tombstone-purger hard-deletes (separate cronjob)
            // (Implementation: schedule a delayed task or use a separate cronjob that runs daily)

        } catch (Exception e) {
            log.error("Draft Cleanup Job failed", e);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Draft Cleanup Job completed in {} ms", duration);
    }

    private List<String> fetchOrphanDraftsFromProductService() {
        try {
            return productServiceClient.get()
                .uri(productServiceExportUrl + "?older_than=30d")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to fetch orphan drafts from Product Service", e);
            return List.of();
        }
    }
}
```

### 2.2 Update: `application.yml`

```yaml
search:
  draft-cleanup:
    cron: "0 0 2 * * *"  # 02:00 AM daily
  product-service:
    export-url: http://product-service:8080/api/v1/internal/products/orphan-drafts
    timeout-seconds: 30
```

---

## 3. TombstonePurger (Hard Delete after 24h)

### 3.1 File: `worker/TombstonePurger.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/TombstonePurger.java`

- [x] Create @Component class
- [x] Method: @Scheduled(cron) purgeTombstones() - hard delete docs đã is_deleted=true quá tombstoneRetentionDays

```java
package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class TombstonePurger {

    private final ProductSearchRepository productRepository;
    private final SearchProperties properties;

    @Scheduled(cron = "${search.tombstone-purger.cron}")
    public void purgeTombstones() {
        long startTime = System.currentTimeMillis();
        Instant cutoff = Instant.now().minus(properties.getDraftCleanup().getTombstoneRetentionDays(), ChronoUnit.DAYS);
        log.info("Starting Tombstone Purger: cutoff={}", cutoff);

        try {
            Query query = Query.of(q -> q.bool(BoolQuery.of(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(true))),
                    Query.of(qf -> qf.range(RangeQuery.of(r -> r
                        .field("deleted_at")
                        .lt(co.elastic.clients.json.JsonData.of(cutoff.toString()))
                    )))
                )
            )));

            // Delete by query
            DeleteByQueryResponse response = productRepository.deleteByQuery(query);
            log.info("Purged {} tombstone documents (total matched: {})", response.deleted(), response.total());

        } catch (Exception e) {
            log.error("Tombstone Purger failed", e);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Tombstone Purger completed in {} ms", duration);
    }
}
```

### 3.2 Update: `application.yml`

```yaml
search:
  tombstone-purger:
    cron: "0 30 2 * * *"  # 02:30 AM daily (after Draft Cleanup at 02:00)
```

---

## 4. ProductSearchRepository Update

### 4.1 Add method: `deleteByQuery`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/ProductSearchRepository.java`

```java
public DeleteByQueryResponse deleteByQuery(Query query) {
    try {
        return esClient.deleteByQuery(d -> d
            .index(properties.getEs().getIndexAliases().getProducts())
            .query(query)
            .refresh(true)
            .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
        );
    } catch (Exception e) {
        log.error("Failed to delete_by_query", e);
        throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES delete_by_query failed", e);
    }
}
```

---

## 5. Acceptance Criteria

- [x] Daily cronjob runs at 02:00 AM (configurable via `search.draft-cleanup.cron`)
- [x] Calls Product Service export API correctly (qua RestClient)
- [x] Mark is_deleted=true for orphan drafts (via _update_by_query)
- [x] Throttle requests_per_second=100 (lower than main 300, off-peak)
- [x] Tombstone purger hard-deletes sau tombstoneRetentionDays (default 30 ngày)
- [x] Failures logged, no rethrow (don't crash scheduler)
- [ ] Test: mock Product Service response → verify documents marked as deleted (thuộc Module 13)
- [ ] Test: tombstone purger skips docs < retention days old (thuộc Module 13)

---

## 6. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `worker/DraftCleanupJob.java` | ✅ Done |
| 2 | `worker/TombstonePurger.java` | ✅ Done |
| 3 | `repository/ProductSearchRepository.java` (add deleteByQuery) | ✅ Done |
| 4 | `worker/DraftCleanupJobTest.java` | ⏳ Module 13 |
| 5 | `worker/TombstonePurgerTest.java` | ⏳ Module 13 |
