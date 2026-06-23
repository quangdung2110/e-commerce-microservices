# Module 09: Product Count Worker (Realtime `categories.product_count`)

> **Status:** Module 9 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 08 (Event Handler)
> **Dependents:** Module 10 (Internal API uses count for display)
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.
>
> Module này handle realtime update của `categories.product_count` để phục vụ navigation sidebar (xem [Search Workflow §4](../search_workflow.md)). Count được update bằng `_update_by_query` arithmetic với sai số ±1-2 chấp nhận được (reconcile qua Full Reindex Job).

---

## 1. Overview

`ProductCountUpdater` service update `categories.product_count` realtime khi có product event thay đổi trạng thái visibility:

| Trigger Event | Action | Conditions |
|---|---|---|
| `PRODUCT_CREATED` (status=ACTIVE) | `product_count += 1` | product ACTIVE, has category_id |
| `PRODUCT_CREATED` (status≠ACTIVE) | Không thay đổi | DRAFT/HIDDEN |
| `SKU_UPDATED.product_status_changed=true → ACTIVE` | `product_count += 1` | auto-reactivate từ HIDDEN |
| `SKU_UPDATED.product_status_changed=true → HIDDEN` | `product_count -= 1` | auto-hide (Inventory Phase 2 FSM) |
| `PRODUCT_DELETED` (was ACTIVE) | `product_count -= 1` | soft delete |
| `PRODUCT_DELETED` (was HIDDEN) | Không thay đổi | tránh double-decrement |
| `CATEGORY_DELETED` | Không thay đổi | audit (đã xử lý docs bên products) |

**Sai số chấp nhận được:** `+= 1` / `-= 1` arithmetic có thể double-apply khi Kafka redeliver. Full Reindex Job sẽ reconcile khi cần.

---

## 2. ProductCountUpdater

### 2.1 File: `worker/ProductCountUpdater.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/ProductCountUpdater.java`

- [ ] Create @Component class
- [ ] Inject UpdateByQueryExecutor, SearchProperties
- [ ] Methods: `incrementCount`, `decrementCount`
- [ ] Each method: build query, execute _update_by_query with arithmetic script

```java
package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCountUpdater {

    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;

    /**
     * Increment product_count by 1 for the given category.
     * Called when: PRODUCT_CREATED (ACTIVE) or SKU_UPDATED.product_status_changed → ACTIVE.
     */
    public void incrementCount(String categoryId, String eventId) {
        try {
            String scriptSource = """
                if (ctx._source.product_count == null) {
                  ctx._source.product_count = 0;
                }
                ctx._source.product_count += 1;
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            execute(categoryId, eventId, scriptSource);
            log.debug("Incremented product_count for category_id={}", categoryId);
        } catch (Exception e) {
            log.error("Failed to increment product_count for category_id={}", categoryId, e);
            // Don't rethrow - non-recoverable error
        }
    }

    /**
     * Decrement product_count by 1 for the given category.
     * Called when: SKU_UPDATED.product_status_changed → HIDDEN, or PRODUCT_DELETED.
     */
    public void decrementCount(String categoryId, String eventId) {
        try {
            String scriptSource = """
                if (ctx._source.product_count == null) {
                  ctx._source.product_count = 0;
                }
                if (ctx._source.product_count > 0) {
                  ctx._source.product_count -= 1;
                }
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            execute(categoryId, eventId, scriptSource);
            log.debug("Decremented product_count for category_id={}", categoryId);
        } catch (Exception e) {
            log.error("Failed to decrement product_count for category_id={}", categoryId, e);
            // Don't rethrow - non-recoverable error
        }
    }

    private void execute(String categoryId, String eventId, String scriptSource) {
        Query query = Query.of(q -> q.term(t -> t.field("category_id").value(categoryId)));
        Map<String, Object> params = Map.of(
            "indexed_at", Instant.now().toString(),
            "event_id", eventId
        );
        UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
            properties.getEs().getIndexAliases().getCategories(),
            query,
            scriptSource,
            params
        );
        log.debug("product_count _update_by_query: category_id={}, updated={}, total={}",
            categoryId, response.updated(), response.total());
    }
}
```

---

## 3. Idempotency & Sai số

### 3.1 Acceptable Drift

**Scenario:** Kafka redelivers event → incrementCount called twice → product_count increases by 2 instead of 1.

**Mitigation:** Acceptable drift of ±1-2 per category. Rationale:
- Category product_count is approximate by nature (UI shows "1.2K products" anyway).
- Full Reindex Job (Module 10) can reconcile when needed.
- 100% accuracy requires idempotency layer (Redis SETNX), too complex for a UI approximation metric.

### 3.2 Avoid Double-Decrement on PRODUCT_DELETED

When product is deleted while still ACTIVE (e.g., admin force-deletes active product):
- `product_count -= 1` for category
- If later admin re-deletes same product (shouldn't happen but race condition possible): product already gone from ES, _update_by_query would skip (no matching docs)
- No double-decrement

When product is deleted while HIDDEN (e.g., CATEGORY_DELETED already moved to HIDDEN):
- `product_count` not changed (only category audit changes)
- If admin re-deletes: same as above

**ProductEventHandlers should check current state before decrement:**

```java
// In handleProductDeleted:
if (event.categoryId() != null) {
    // Only decrement if product was ACTIVE (need to check current ES state)
    // OR rely on event payload to know previous status (preferred)
    productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
}
```

**Note:** `ProductDeletedEvent` payload không có `product_status` field. Cần query ES trước để check status. Alternative: pass previous status in event payload (requires Product Service to update payload schema). For now, accept slight over-decrement (rare edge case).

---

## 4. Integration with Event Handlers

ProductEventHandlers should call ProductCountUpdater at appropriate times:

```java
// In handleProductCreated:
if ("ACTIVE".equals(event.status()) && event.categoryId() != null) {
    productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
}

// In handleSkuUpdated (if product_status_changed=true):
if (Boolean.TRUE.equals(event.productStatusChanged())) {
    String newStatus = event.newProductStatus();
    if ("ACTIVE".equals(newStatus) && event.categoryId() != null) {
        productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
    } else if ("HIDDEN".equals(newStatus) && event.categoryId() != null) {
        productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
    }
}

// In handleSkuCreated:
if ("ACTIVE".equals(event.productStatus()) && event.categoryId() != null) {
    productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
}

// In handleProductDeleted:
if (event.categoryId() != null) {
    productCountUpdater.decrementCount(event.categoryId(), envelope.getEventId());
}
```

---

## 5. Acceptance Criteria

- [ ] `incrementCount` correctly updates `categories.product_count += 1` via _update_by_query
- [ ] `decrementCount` correctly updates `categories.product_count -= 1` (with min 0 guard)
- [ ] Throttle `requests_per_second=300` applied via UpdateByQueryExecutor
- [ ] Conflicts proceed (no rollback on conflict)
- [ ] Idempotency acceptable: ±1-2 drift per category
- [ ] Failures logged + metric (no rethrow to avoid blocking event processing)
- [ ] Integration test: PRODUCT_CREATED (ACTIVE) → product_count increases
- [ ] Integration test: PRODUCT_DELETED → product_count decreases

---

## 6. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `worker/ProductCountUpdater.java` | ⏳ Not yet |
| 2 | `worker/ProductCountUpdaterTest.java` | ⏳ Not yet |
| 3 | `worker/ProductCountUpdaterIT.java` (Testcontainers) | ⏳ Not yet |
| 4 | `worker/handler/ProductEventHandlers.java` (add calls) | ⏳ Not yet |
