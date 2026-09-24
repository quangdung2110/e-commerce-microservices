---
doc_id: product-detail-bff-adr-001
service: product-detail-bff
status: accepted
date: 2026-09-09
decision_owners:
  - Storefront team
  - Inventory Service team
---

# ADR-001 — Inventory-owned batch availability read thay vì BFF projection

## Status

`Accepted`.

Amended bởi [ADR-003](./ADR-003-checkout-preview-order-reserve-boundary.md): Checkout chỉ
gọi allocation Preview, Order là caller duy nhất của Inventory Reserve. Câu "Checkout vẫn
reserve trực tiếp tại Inventory" dưới đây là lịch sử tại thời điểm quyết định. Quyết định
Inventory-owned batch availability read giữ nguyên. Con số timeout/bulkhead/circuit
breaker mà ADR này yêu cầu được chốt ở
[ADR-004](./ADR-004-resilience-budget-and-mesh-split.md).

## Context

PDP cần hiển thị khả dụng theo variant nhưng Product không được giữ stock replica.
Thiết kế cũ để Product Detail BFF consume `stock-snapshot.v1`, persist
`pdp_sku_availability` và bootstrap từ Inventory export. Contract đó đã bị thay thế:
Inventory Release 1 chỉ phát/export boolean availability cho Search sau readiness
registration. BFF không phải consumer của event/export đó và không được suy ra quyền từ
Search.

Giữ projector cũ sẽ thêm database, consumer, recovery state và runbook vào một lớp
composition, đồng thời buộc BFF giữ quantity mà Inventory không muốn expose cho PDP.

## Decision

- Inventory cung cấp `POST /internal/inventory/v1/sku-availabilities:batch-get`, chỉ
  cho service identity `product-detail-bff`.
- Sau khi Product trả catalog-active SKU, BFF gọi endpoint một lần cho toàn bộ SKU và
  compose `IN_STOCK`, `OUT_OF_STOCK` hoặc `UNKNOWN` vào PDP response.
- Inventory sở hữu quy tắc warehouse-aware availability, `SkuAvailabilityState`,
  revision, freshness/pending policy, cache/read replica và recovery. BFF không biết
  implementation storage của Inventory.
- BFF không có PostgreSQL/Redis stock projection, Kafka consumer, export client,
  bootstrap, replay hoặc revision guard. Cache CDN/in-process chỉ là optional TTL-bound
  optimization, không là source of truth.
- Nếu Inventory query timeout/503 hoặc không trả item đáng tin cậy, BFF trả `UNKNOWN`;
  catalog vẫn phục vụ. Checkout vẫn reserve trực tiếp tại Inventory.
- Batch response không mang quantity, warehouse, allocation hay reservation data.

## Alternatives considered

- **BFF giữ local event projection.** Hợp lệ khi một dedicated PDP read-model service
  có consumer contract, snapshot/replay, SLO và ownership riêng. Không chọn cho BFF
  hiện tại vì chỉ có một PDP client và Inventory chưa cấp event/export contract cho nó.
- **Dùng Search làm PDP source.** Search không là catalog point-read authority, không
  là fallback stock store và response index không có contract PDP freshness.
- **Live query từng SKU.** Tạo N+1 fan-out, latency và blast radius không cần thiết;
  thay bằng một bounded batch API.
- **Đưa stock vào Product.** Tạo stock replica ở sai bounded context và mâu thuẫn với
  Inventory ownership.

## Consequences và verification

BFF có thêm synchronous Inventory dependency nhưng không có stateful data pipeline.
Inventory phải scale Availability Read API/read model theo PDP traffic và trả `UNKNOWN`
khi không thể khẳng định UI state. BFF phải dùng timeout, bulkhead và circuit breaker
riêng để dependency lỗi không biến catalog thành lỗi.

Contract test phải chứng minh response đủ một item cho mỗi SKU, không lộ quantity,
`UNKNOWN` được giữ nguyên, timeout/503 degrade đúng và Checkout Reserve là quyết định
duy nhất chống oversell. Không được triển khai `pdp_sku_availability` hay dùng lại
Search event/export cho PDP.
