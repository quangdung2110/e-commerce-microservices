---
doc_id: product-detail-bff-adr-001
service: product-detail-bff
status: proposed
date: 2026-09-06
decision_owners:
  - Storefront team
  - Inventory Service team
---

# ADR-001 — Availability projection cục bộ thay vì live Inventory query

## Context

PDP cần hiển thị khả dụng theo variant nhưng Product không được giữ stock replica.
Search có stock projection cho browse, song không là catalog point-read authority cho PDP.

## Decision

Product Detail BFF consume `stock-snapshot.v1` và giữ projection per-SKU có revision.
Mỗi PDP đọc catalog đồng bộ từ Product và availability cục bộ từ projection. Inventory
export chỉ dùng bootstrap/recovery; Checkout vẫn reserve trực tiếp tại Inventory.

## Alternative đã cân nhắc — live availability batch query

BFF gọi một API batch availability chuyên dụng của Inventory trên mỗi PDP request.
Phương án này cho dữ liệu tươi hơn và không cần projection riêng, nhưng biến Inventory
thành synchronous dependency của traffic đọc PDP, tăng blast radius khi Inventory lỗi,
và vẫn không thay thế Reserve. Không chọn ở giai đoạn này; chỉ xem lại khi freshness SLO
đòi hỏi dữ liệu tươi hơn budget projection.

## Alternatives bị loại

- Dùng Search làm PDP source: Search không có PDP point-read/catalog authority.
- Lưu quantity trong Product: tạo stale replica và mâu thuẫn Product ADR-009.

## Consequences và verification

Thêm database/consumer/runbook cho BFF và chấp nhận `UNKNOWN` khi projection không tươi.
Chỉ chuyển ADR sang `Accepted` sau khi Inventory v1 provider tồn tại, bootstrap/replay
hội tụ, contract test pass và Checkout vẫn là nơi duy nhất có quyền reserve.
