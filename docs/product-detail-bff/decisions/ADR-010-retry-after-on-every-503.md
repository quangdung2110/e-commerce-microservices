---
doc_id: product-detail-bff-adr-010
service: product-detail-bff
status: accepted
date: 2026-10-16
decision_owners:
  - Product Detail BFF team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md
  - java/services/product-detail-bff/src/main/java/com/ecommerce/pdp/api/Problems.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/ProductBudgetTest.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/ProductBulkheadTest.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/DependencyIsolationTest.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/ProductDetailApiTest.java
---

# ADR-010 — `Retry-After` trên mọi `503`, không chỉ khi circuit mở

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Sửa mục 4 của ADR-004 (cột `503 PDP`) và mục `budget_skipped` của ADR-007.

## Context

ADR-004 §4 chỉ cho `503` kèm `Retry-After` khi circuit Product mở; timeout, lỗi kết nối, `5xx`, bulkhead đầy và hết budget trả `503` không header, và ba test khẳng định `doesNotExist("Retry-After")`. [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md) yêu cầu mọi `503` `retryable: true` mang `Retry-After`. Load test ([contracts ADR-010](../../../contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md)) cho thấy hệ quả: 19 trên 19 `503` của PDP không có header, dù contract khai báo nó.

## Decision

`Problems.unavailable` luôn đặt `Retry-After`: thời gian mở còn lại khi circuit Product mở (như trước), ngược lại `2` giây (`DEFAULT_RETRY_AFTER_SECONDS`). Mọi `503` của PDP có `retryable = true` nên không còn nhánh thiếu. Circuit breaker, bulkhead và budget không đổi; không retry mới ở PDP. Bảng phân loại của ADR-004 §4 giữ nguyên, trừ cột `503 PDP`: giờ luôn kèm `Retry-After`.

## Alternatives considered

- **Giữ nguyên.** Không chọn: ADR-005 bắt buộc, và client không có cách phân biệt `503` thoáng qua với `503` do circuit.
- **Giá trị theo loại lỗi.** Không chọn: PDP không có ước lượng tốt hơn `2` giây cho một timeout đơn lẻ; giá trị circuit mở đã là ước lượng thật.

## Consequences

Client đọc được khoảng chờ trên mọi `503`. Ba test đổi khẳng định sang `Retry-After: 2`. Cache vẫn `no-store`.

## Migration impact

Code: `Problems`. Test: `ProductBudgetTest`, `ProductBulkheadTest`, `DependencyIsolationTest`, `ProductDetailApiTest`. Docs: `interfaces.md`.

## Verification

65 test của PDP xanh; `ProductDetailApiTest.productServerErrorIsUnavailable` kiểm `Retry-After: 2` khi circuit chưa mở, `DependencyIsolationTest` kiểm giá trị circuit khi đã mở.
