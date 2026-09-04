---
doc_id: search-adr-016
service: search
status: accepted
date: 2026-09-05
decision_owners:
  - Search Service team
evidence:
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
  - docs/review/review_db_schema.md
---

# ADR-016 — Snapshot rating Review khi reindex

## Context

Rating là field do Review sở hữu, nhưng Build của full reindex trước đây chỉ import
catalog từ Product và stock từ Inventory. Replay `t.review.events` trong Catch-up
chỉ bù mutation mới; nó không tạo rating cho product không phát event trong cửa sổ
replay hoặc có event cũ hơn retention. Index mới vì thế có thể mất hoàn toàn rating
đã có trước khi chạy reindex.

`updated_at` không phải ordering guard: hai mutation có thể cùng timestamp và clock
không phải nguồn thứ tự. Snapshot export cũng có race với event nếu không biết chính
xác offset nào cần replay sau import.

## Decision

Review cấp hai contract v1 cho Search:

1. `GET /internal/v1/reviews/rating-aggregates` keyset-export state hiện tại theo
   `product_id`. Export chụp `replay_from_offsets` của `t.review.events` **trước**
   khi scan và trả lại cùng một tập offset cho toàn bộ export session.
2. `com.ecommerce.review.metrics-updated.v1` mang snapshot aggregate cùng
   `review_revision` đơn điệu per `product_id`.

Build chạy catalog trước, khởi tạo default rating `0/0` khi tạo SKU, rồi import
rating aggregate snapshot. Snapshot và event replay ghi qua cùng guard
`review_revision > document.review_revision`, không upsert SKU và chỉ sửa các field
thuộc Review. Catch-up seek inclusive vào từng `replay_from_offsets` sau import.

## Consequences

Full reindex có thêm dependency Review; Review export lỗi, thiếu offset hoặc không
đủ contract revision sẽ chặn Cutover. Đổi mapping `review_version` sang
`review_revision` là thay đổi mapping, nên chỉ có hiệu lực qua versioned-index
reindex.

Đổi lại, snapshot có thể được lấy giữa lúc dữ liệu thay đổi mà không mất mutation:
event trước snapshot bị guard bỏ qua nếu cũ, event sau watermark được replay và
revision lớn hơn sẽ hội tụ. Product chưa từng có review vẫn có default xác định.

## Relationship to existing ADRs

ADR này mở rộng nguồn dữ liệu của pha Build; không thay đổi ba pha
Build/Catch-up/Cutover hay alias swap của ADR-006. Nó cụ thể hóa “contract revision
riêng” cho Review trong ADR-015.

## Verification

Contract test phải chứng minh snapshot và event có cùng `review_revision`; test
reindex phải bao phủ aggregate tồn tại trước Build, mutation trong lúc export,
replay duplicate/old, product không có review và Review export failure không được
Cutover.
