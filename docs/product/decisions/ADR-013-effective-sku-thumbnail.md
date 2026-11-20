---
doc_id: product-adr-013
service: product
status: accepted
date: 2026-09-04
decision_owners:
  - Product Service team
---

# ADR-013 — Product resolve effective thumbnail theo SKU

## Status

`Accepted`. Phần fan-out của quyết định được làm rõ bởi
[ADR-020](./ADR-020-media-fan-out-follows-snapshot-content.md): media cấp Product phát lại snapshot của
mọi SKU, không chỉ các SKU đang fallback.

## Context

Search lưu một document cho mỗi SKU và cần đúng một thumbnail ổn định cho product
card. `media[]` trong catalog snapshot không đủ để Search tự quyết định: media có
thể nằm ở Product hoặc SKU, có `IMAGE` lẫn `VIDEO`, và quy tắc fallback là nghiệp
vụ catalog do Product sở hữu.

## Decision

Product tính `sku.thumbnail_storage_key` trong mọi `sku-upserted` snapshot theo thứ
tự: `IMAGE` của chính SKU có `sort_order` nhỏ nhất; nếu không có, `IMAGE` của
Product có `sort_order` nhỏ nhất; nếu vẫn không có thì `null`. Chỉ `storage_key`
được phát, không phát CDN URL.

Mọi mutation media tăng `catalog_revision` một lần và phát lại snapshot cho mọi SKU
có effective thumbnail thay đổi: media SKU chỉ ảnh hưởng SKU đó; media Product ảnh
hưởng các SKU đang fallback Product-level. Search chỉ materialize field này; response
adapter của Search dựng URL CDN hoặc placeholder.

Cùng field xuất hiện trong schema `Sku` của HTTP API v1, nên export nội bộ dùng cho
full reindex mang đúng giá trị mà event mang. Nếu chỉ event có field này, phase build
catalog của reindex sẽ buộc Search tự derive từ `media[]` — đúng thứ mà quyết định
này cấm.

## Consequences

Snapshot có thể fan-out nhiều SKU khi media Product-level đổi, nhưng downstream không
phải join hay gọi ngược Product. `null` là trạng thái hợp lệ, không phải lỗi contract.

## Verification

Contract fixture bao phủ: SKU image thắng Product image, fallback đúng thứ tự, VIDEO
không được chọn, không có image trả `null`, và media Product fan-out đúng tập SKU.
Thêm một case đối chiếu export với event để hai đường không lệch nhau.
