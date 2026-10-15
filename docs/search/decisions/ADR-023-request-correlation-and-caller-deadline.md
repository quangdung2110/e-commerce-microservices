---
doc_id: search-adr-023
service: search
status: accepted
date: 2026-10-15
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-002-correlation-and-trace-propagation.md
  - contracts/decisions/ADR-003-deadline-propagation.md
  - java/services/search-service/src/main/java/com/ecommerce/search/common/RequestCorrelation.java
  - java/services/search-service/src/main/java/com/ecommerce/search/config/RequestIdFilter.java
  - java/services/search-service/src/main/java/com/ecommerce/search/config/WebConfig.java
  - java/services/search-service/src/main/resources/application.yml
  - java/services/search-service/src/test/java/com/ecommerce/search/config/RequestIdFilterTest.java
  - java/services/search-service/src/test/java/com/ecommerce/search/config/WebConfigOutboundHeadersTest.java
---

# ADR-023 — Request ID, trace ID, caller deadline và timeout export của Search

## Status

`Accepted`. Áp dụng [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)
và [ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) cho Search, vừa là bên nhận
(Gateway) vừa là bên gọi (export của Product/Inventory/Review, handshake với Inventory). Làm rõ
[ADR-022](./ADR-022-post-implementation-audit-corrections.md); không thay thế quyết định nào.

## Context

- `RequestIdFilter` đã đặt MDC `request_id` và trả header, nhưng chỉ giữ ID dài 16–64 ký tự (để hợp lệ
  với `trace_id` của Problem schema), không đọc `traceparent` và không đọc deadline của caller.
- `restClientBuilder` gắn `X-Service-Identity` cho mọi lời gọi export/handshake, nhưng không gửi request ID
  hay timeout. Các lời gọi này chạy trên thread consumer hoặc job, không có request đến.
- `search.export.timeout` là `30s`, bằng timeout mesh `30 giây` của `/internal/v1/products/export`,
  `/exports/inventory/v1/**` và `/internal/v1/reviews/rating-aggregate-exports**` (mesh §7), nên quy tắc
  "caller nhỏ hơn mesh ít nhất 1 giây" bị vi phạm.

## Decision

1. Mở rộng `RequestIdFilter` (không thêm filter thứ hai): giữ quy tắc ID 16–64 ký tự của Search, thêm
   MDC `trace_id` từ `traceparent` hợp lệ, đọc `X-Request-Timeout-Ms`, đếm
   (`http.server.caller_deadline_exceeded{route}`) và ghi WARN khi request vượt, không hủy công việc.
2. Mỗi lời gọi ra của `restClientBuilder` mang `X-Request-Id` mới (UUID, ADR-002 quy tắc 4) và
   `X-Request-Timeout-Ms` bằng `search.export.timeout` (ADR-003 quy tắc 3.1). Search không có
   `traceparent` để gửi tiếp và không bịa.
3. `search.export.timeout` giảm từ `30s` xuống `25s`, để nhỏ hơn timeout mesh 5 giây. Một trang export
   chậm hơn 25 giây trước đây có thể thành công sát 30 giây; nay nó thất bại sớm hơn 5 giây và đi theo
   đường thử lại của job (nack/backoff, không đổi).
4. Log pattern và `logback-spring.xml` thêm MDC `trace_id`.

## Alternatives considered

- **Nới timeout mesh lên 35 giây.** Không chọn: giữ thread của waypoint lâu hơn cho mọi lời gọi export.
- **Bỏ quy tắc ID 16–64 để khớp ADR-002.** Không chọn: nó bảo vệ `trace_id` của Problem; các ID ngắn hơn
  16 ký tự bị thay bằng UUID và vẫn không gây lỗi.

## Consequences

Log Search tra chéo được theo `request_id` ở cả hai chiều. Export chậm hơn 25 giây thất bại sớm hơn một chút.

## Migration impact

Code: `RequestCorrelation` (mới), `RequestIdFilter`, `WebConfig`. Config: `application.yml`
(`search.export.timeout`), `logback-spring.xml`. Không đổi contract.

## Verification

`RequestIdFilterTest` (MDC `trace_id`, ID giữ nguyên, overrun được đếm), `WebConfigOutboundHeadersTest`
(`X-Request-Id` khác nhau mỗi lời gọi, `X-Request-Timeout-Ms: 25000`, identity), `SnapshotExportClientTest` và
`InventoryReadinessClientTest` không đổi.
