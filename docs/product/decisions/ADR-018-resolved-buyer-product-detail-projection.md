---
doc_id: product-adr-018
service: product
status: proposed
date: 2026-09-25
decision_owners:
  - Product Service team
  - Storefront team
supersedes: null
amends:
  - ADR-015
evidence:
  - contracts/http/product/v1/openapi.yaml
  - contracts/http/product-detail-bff/v1/openapi.yaml
  - docs/product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md
  - java/services/product-service/src/main/java/com/platform/productservice/service/support/BuyerDetailAssembler.java
  - java/services/product-service/src/test/java/com/platform/productservice/integration/BuyerProductDetailIntegrationTest.java
---

# ADR-018 — Buyer Product Detail read trả projection đã resolve

## Status

`Proposed`. Amend [ADR-015](./ADR-015-buyer-product-detail-read.md) ở hình dạng response;
endpoint, caller, mTLS và nguyên tắc "không có stock" của ADR-015 giữ nguyên. Quyết định
phía consumer nằm ở
[Product Detail BFF ADR-005](../../product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md).

## Context

`GET /internal/v1/product-details/{slug}` đang trả lại schema `Product` của admin API:
`category_id` trần, `attributes`/`variant_attributes` chỉ là code, media không giới hạn
theo PDP, cùng `status`, `created_at` và các field chỉ admin cần. PDP cần breadcrumb
category, bảng thông số có tên/nhãn, bộ chọn biến thể và gallery. Nếu BFF tự resolve, nó
phải gọi API category/attribute vốn là admin API yêu cầu role `ADMIN`, thêm lời gọi vào
hot path và tự áp quy tắc thứ tự mà Product sở hữu.

Product có đủ dữ liệu: category tree (path dẫn xuất như `CategoryExport.path`), tên
attribute, label và `sort_order` của option, mapping `is_variant`, và `product_media`.
Theo contract hiện tại một Product có thể có 100 media cấp Product và 500 SKU × 100 media,
nên response PDP không giới hạn có thể chứa hơn 50 nghìn media.

## Decision

Buyer-read trả schema riêng `BuyerProductDetail`, resolve toàn bộ trong Product:

- **Category:** `category_id`, `name`, `slug` và `path` từ category gốc tới category lá
  của Product.
- **Thuộc tính:** `attributes[]` gồm mapping `is_variant=false` có giá trị; mỗi phần tử có
  `code`, `name`, `value_type`, `value` và `value_label` (label option khi `SELECT`, còn lại
  `null`). Chưa có đơn vị đo; `NUMBER` trả giá trị trần.
- **Trục biến thể:** `variant_axes[]` gồm mapping `is_variant=true` có ít nhất một SKU
  `ACTIVE` mang giá trị; `options[]` chỉ gồm giá trị đang được SKU `ACTIVE` dùng. `SELECT`
  sắp theo option `sort_order` rồi `code`; kiểu khác sắp theo giá trị.
- **Thứ tự thuộc tính và trục:** theo `name` bằng collation tiếng Việt, tie-break `code`.
  Chưa có thứ tự hiển thị do admin cấu hình.
- **Media:** danh sách phẳng, tối đa 20 media cấp Product và tối đa 10 media cho mỗi SKU
  `ACTIVE`, mỗi nhóm lấy theo `sort_order` rồi `media_id`. Không có media của SKU
  `INACTIVE`. Chỉ trả `storage_key`, không trả CDN URL (giữ ADR-013).
- **SKU:** chỉ SKU `ACTIVE`, không có `status`; giữ `thumbnail_storage_key` theo ADR-013,
  resolve trên toàn bộ media chứ không trên tập media đã cắt.
- **Không ETag:** category và nhãn attribute/option không thuộc Product aggregate nên đổi
  chúng không tăng `catalog_revision`. `catalog_revision` vẫn được trả nhưng không còn
  định danh đầy đủ response, vì vậy buyer-read không phát `ETag`.

Product `DRAFT`/`HIDDEN`, slug không tồn tại hoặc không còn SKU `ACTIVE` vẫn trả `404`.

## Alternatives considered

- **BFF gọi thêm API category/attribute.** Thêm hai lời gọi vào hot path, phải mở admin
  API cho service identity và để BFF tự áp quy tắc thứ tự của Product.
- **Nhúng nhãn vào Product aggregate.** Mỗi lần đổi tên attribute hoặc category phải
  fan-out revision tới mọi Product liên quan; không đáng cho một read path.
- **Product trả CDN URL.** Kéo cấu hình CDN vào Product và mâu thuẫn ADR-013; Search đã
  dựng URL ở adapter của mình.
- **Không giới hạn media.** Payload PDP tăng theo số SKU không kiểm soát được.

## Consequences

Product phải join category path, định nghĩa attribute và option cho mỗi buyer-read; mục
tiêu p99 ≤ 250 ms giữ nguyên và phải được load test lại. Response xấu nhất khoảng 5 020
media, bị chặn trên. Admin API, export và snapshot cho Search không đổi.

## Migration impact

- Code: `InternalCatalogController`/`ProductQueryService.getBuyerDetail` hiện trả
  `ProductResponse` kèm `ETag`; phải đổi sang DTO buyer mới và bỏ `ETag`.
- Contract: `contracts/http/product/v1/openapi.yaml` thêm `BuyerProductDetail` và các
  schema con. Consumer duy nhất là Product Detail BFF, chưa được implement, nên không cần
  version song song.
- Không có migration database.

## Verification

Contract test cho buyer-read chứng minh: path đúng thứ tự gốc → lá; `value_label` chỉ có
với `SELECT`; attribute/trục sắp theo tên với tie-break `code`; option chỉ gồm giá trị SKU
`ACTIVE` dùng và đúng thứ tự; cắt 20/10 media đúng thứ tự; không có media hoặc SKU
`INACTIVE`; `thumbnail_storage_key` không đổi khi media bị cắt; không có `ETag` và không có
field stock. Load test xác nhận p99 ≤ 250 ms.
