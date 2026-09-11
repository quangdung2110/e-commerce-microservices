---
doc_id: product-adr-016
service: product
status: accepted
date: 2026-09-11
decision_owners:
  - Product Service team
  - Promotion Service team
supersedes:
  - ADR-011
evidence:
  - docs/product/data.md
  - docs/product/workflows.md
  - contracts/events/product/v1/schemas/product-markdown-snapshot.schema.json
---

# ADR-016 — Markdown snapshot phân biệt ACTIVE và INACTIVE

## Status

`Accepted` ở mức thiết kế; chưa implemented. Supersede ADR-011. Giữ quyết định Product
không có scheduler/Redis và Markdown Consumer là background business workload duy nhất;
thay schema “mọi state đều có rule đầy đủ” bằng state-discriminated snapshot.

## Context

Khi không còn winning markdown, Promotion phải gửi một current-state tombstone để Product
reset giá và advance `source_revision`. Bắt `INACTIVE` mang rule, discount và interval là
không đúng nghĩa và không biểu diễn được Product chưa từng có winner. Product projection
hiện dùng NOT NULL cho các field này nên cũng không persist được tombstone tối thiểu.

## Decision

- Product chỉ consume `com.ecommerce.promotion.product-markdown-snapshot.v1`, partition
  theo product và apply khi `source_revision` lớn hơn local projection.
- `ACTIVE` bắt buộc `promotion_rule_id`, discount và `[starts_at,ends_at)`; không có
  `inactive_reason`.
- `INACTIVE` bắt buộc `inactive_reason`, không mang rule/discount/interval. Product lưu
  nullable rule configuration, advance revision và reset mọi SKU về base price trong cùng
  transaction với catalog outbox.
- Snapshot ACTIVE đến sau `ends_at` vẫn advance revision nhưng Product materialize
  INACTIVE reason `EXPIRED`, không hồi sinh giá cũ.
- Product không có markdown scheduler hoặc Redis. Consumer ack sau DB commit, lock Product
  rồi SKU; retry/DLQ được phục hồi bằng replay latest snapshot từ Promotion.
- Nếu Product đã bị hard delete, markdown event đến muộn được ack no-op. Product tombstone
  đã phát riêng cho Promotion/Search trước delete; Promotion không gửi INACTIVE ngược về
  Product đã mất.

## Alternatives considered

- Giữ metadata rule cũ trong INACTIVE: khó biểu diễn no-winner ban đầu và dễ bị đọc nhầm
  thành effective configuration.
- Xóa projection khi INACTIVE: stale ACTIVE event có thể hồi sinh giá vì mất revision
  tombstone.
- Product safety scheduler: trùng ownership schedule với Promotion.

## Consequences

Contract phản ánh đúng current state và stale ACTIVE không thể hồi sinh markdown. Đổi lại
Product schema có nullable columns/conditional checks, consumer phải hỗ trợ hai payload
shape và rollout contract cần consumer-first.

## Migration impact

Thay JSON Schema bằng `oneOf`, thêm inactive fixture, migrate projection columns nullable,
thêm `inactive_reason` và partial unique rule index. Vì contract đang proposed, cập nhật v1
trước implementation; nếu đã deploy thì tạo v2 và chạy dual-read trước cutover.

## Verification

Contract test ACTIVE đủ field, INACTIVE tombstone và reject payload lai. DB test conditional
CHECK/partial unique. Consumer test ACTIVE→INACTIVE, inactive-first, stale/duplicate, ACTIVE
đến sau ends_at, missing Product và outbox/catalog revision atomicity.
