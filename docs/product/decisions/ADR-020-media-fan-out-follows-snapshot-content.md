---
doc_id: product-adr-020
service: product
status: accepted
date: 2026-10-09
decision_owners:
  - Product Service team
supersedes: null
amends:
  - ADR-013
evidence:
  - java/services/product-service/src/main/java/com/platform/productservice/service/MediaCommandService.java
  - java/services/product-service/src/test/java/com/platform/productservice/integration/MediaThumbnailIntegrationTest.java
  - contracts/events/product/v1/schemas/catalog-common.schema.json
---

# ADR-020 — Fan-out media theo nội dung snapshot

## Status

`Accepted`. Làm rõ đoạn fan-out của [ADR-013](./ADR-013-effective-sku-thumbnail.md); cách resolve
`thumbnail_storage_key` và mọi quyết định khác của ADR-013 giữ nguyên.

## Context

ADR-013 ghi mọi mutation media "phát lại snapshot cho mọi SKU có effective thumbnail thay đổi", tức media
Product chỉ ảnh hưởng các SKU đang fallback. Code và test lại phát snapshot cho **mọi** SKU khi media cấp
Product đổi (`MediaThumbnailIntegrationTest.productMediaFanOutHitsFallbackSkusOnly`: "cả hai SKU nhận snapshot
mới"). Lý do: schema `sku` chỉ có `media[]` ở cấp SKU, nên Product đưa vào mỗi snapshot media của SKU cộng
media cấp Product (`ProductCommandIntegrationTest` kiểm "Snapshot SKU mang cả media của SKU lẫn media cấp
Product"). Một SKU không fallback vẫn đổi `media[]` khi ảnh Product đổi, và Search lưu `media[]` đó.

## Decision

Tập SKU được phát lại sau một mutation media là tập SKU có **nội dung snapshot** đổi, không phải tập SKU có
thumbnail đổi:

- Media gắn SKU (tạo/sửa/xóa, kể cả chuyển media sang SKU khác): snapshot của SKU cũ và SKU mới.
- Media cấp Product: snapshot của mọi SKU của Product.

Chỉ SKU đang fallback Product-level đổi `thumbnail_storage_key`; các SKU còn lại nhận snapshot với thumbnail
cũ nhưng `media[]` mới. Mọi snapshot mang cùng `catalog_revision` của lần mutation.

## Alternatives considered

- **Chỉ phát các SKU đổi thumbnail (đúng chữ ADR-013).** Search sẽ giữ `media[]` cũ ở các SKU còn lại, trái với
  nguyên tắc snapshot của SKU luôn phản ánh Product context cùng revision.
- **Bỏ media cấp Product khỏi `media[]` của SKU.** Breaking với consumer, và Search cần nó để hiển thị gallery.

## Consequences

Media cấp Product đổi tạo N snapshot cho Product có N SKU (N ≤ 500). Chấp nhận vì thao tác này do admin làm,
hiếm, và metric số SKU fan-out theo media mutation đã nằm trong [operations](../operations.md).

## Migration impact

Không đổi code, contract hay dữ liệu: ADR này sửa tài liệu cho khớp hành vi đã chạy. Cập nhật
`interfaces.md`, `workflows.md`, `development.md`.

## Verification

`MediaThumbnailIntegrationTest.productMediaFanOutHitsFallbackSkusOnly` (hai SKU nhận snapshot, chỉ SKU
fallback đổi thumbnail) và `ProductCommandIntegrationTest` (media cấp Product ảnh hưởng mọi SKU, media gắn SKU
chỉ ảnh hưởng SKU đó).
