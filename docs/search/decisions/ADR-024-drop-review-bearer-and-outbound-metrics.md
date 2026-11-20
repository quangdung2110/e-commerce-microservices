---
doc_id: search-adr-024
service: search
status: accepted
date: 2026-10-15
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-004-service-identity-allowlist.md
  - java/services/search-service/src/main/java/com/ecommerce/search/worker/client/SnapshotExportClient.java
  - java/services/search-service/src/main/java/com/ecommerce/search/config/SearchProperties.java
  - java/services/search-service/src/main/java/com/ecommerce/search/config/WebConfig.java
  - java/services/search-service/src/test/java/com/ecommerce/search/config/WebConfigOutboundHeadersTest.java
  - java/services/search-service/src/test/java/com/ecommerce/search/worker/client/SnapshotExportClientTest.java
---

# ADR-024 — Bỏ bearer token Review và đo các lời gọi export

## Status

`Accepted`. Áp dụng [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md) và
[Review ADR-015](../../review/decisions/ADR-015-service-identity-header-replaces-bearer-tokens.md); bổ sung
[ADR-023](./ADR-023-request-correlation-and-caller-deadline.md).

## Context

- Search gắn `Authorization: Bearer <REVIEW_INTERNAL_TOKEN>` cho các lời gọi export của Review
  (`reviewServiceIdentity`). Review nay kiểm `X-Service-Identity: search-service`, và Search đã gửi header đó trên
  mọi lời gọi qua `restClientBuilder`.
- Các lời gọi ra (export Product, Inventory, Review và handshake Inventory) dùng chung một `RestClient` không
  pool riêng, không breaker, không metric theo dependency. Có lý do: chúng chạy trên consumer hoặc job, và lỗi
  được xử lý bằng nack/redelivery (`operations.md §5`). Nhưng khi một export chậm hoặc lỗi, không có số đo để biết
  provider nào.

## Decision

1. Bỏ `search.export.review-token` / `REVIEW_INTERNAL_TOKEN` khỏi `SearchProperties.Export`, `application.yml` và
   `SnapshotExportClient`. Review nhận `X-Service-Identity: search-service` như mọi provider khác.
2. Interceptor của `restClientBuilder` đo mỗi lời gọi ra bằng timer `search.outbound.http`, nhãn `dependency`
   (`product`, `inventory`, `review`, `other`, suy ra từ host của base URL cấu hình) và `outcome`
   (`success`, `client_error`, `server_error`, `io_error`). Nhãn là tập hữu hạn; không có ID hay path.
3. Không thêm circuit breaker hay bulkhead: lỗi tiếp tục đi theo đường nack/redelivery và job retry hiện có.

## Alternatives considered

- **Breaker và bulkhead cho export.** Không chọn: export là batch tuần tự, đồng thời bị giới hạn bởi số session;
  breaker sẽ che lỗi mà job cần thấy để dừng đúng (`FullReindexJob`).
- **Metric theo path.** Không chọn: cardinality theo cursor/session.

## Consequences

Search không còn secret cho Review; bớt một cấu hình bắt buộc để rating build chạy. Có số đo độ trễ và tỷ lệ lỗi
theo provider cho mọi lời gọi export.

## Migration impact

Code: `SearchProperties.Export` (bỏ một thành phần), `SnapshotExportClient`, `WebConfig`. Config: `application.yml`.
Test: sáu test dựng `Export` bỏ tham số token. Compose: bỏ `REVIEW_INTERNAL_TOKEN`.

## Verification

`SnapshotExportClientTest` (không có `Authorization`, có `X-Service-Identity: search-service`),
`WebConfigOutboundHeadersTest` (timer theo dependency và outcome, kể cả lỗi 5xx).
