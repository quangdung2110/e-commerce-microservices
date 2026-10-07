---
doc_id: product-detail-bff-adr-005
service: product-detail-bff
status: accepted
date: 2026-09-25
decision_owners:
  - Storefront team
  - Product Service team
supersedes: null
amends: null
evidence:
  - contracts/http/product-detail-bff/v1/openapi.yaml
  - contracts/http/product/v1/openapi.yaml
  - docs/product/decisions/ADR-018-resolved-buyer-product-detail-projection.md
---

# ADR-005 — Phạm vi catalog của PDP và URL media

## Status

`Accepted`. Không supersede hay amend ADR nào của BFF. Phía provider là
[Product ADR-018](../../product/decisions/ADR-018-resolved-buyer-product-detail-projection.md),
amend Product ADR-015.

## Context

Public contract v1 chỉ có tên, mô tả, giá, `variant_attributes` dạng code và một
`thumbnail_url` mỗi SKU. PDP còn cần breadcrumb category, bảng thông số, bộ chọn biến thể
có nhãn và gallery ảnh/video. Thiếu các phần đó, storefront phải gọi thêm API sau khi có
PDP, đúng kiểu gọi nối tiếp mà BFF được tạo ra để tránh.

Product buyer-read trả `storage_key` và `thumbnail_storage_key` được phân loại `INTERNAL`,
nhưng không tài liệu nào của BFF nói ai dựng URL công khai. Product ADR-013 cấm Product
phát CDN URL; Search dựng URL ở adapter của mình từ CDN base URL và URL placeholder cấu hình.

## Decision

- Public response có thêm `category` (kèm `path` breadcrumb), `attributes[]` (bảng thông
  số), `variant_axes[]` (bộ chọn biến thể) và `media[]` (gallery phẳng có `sku_id`).
- **Product resolve, BFF map.** Tên, nhãn, thứ tự và giới hạn media do Product quyết định
  trong buyer-read. BFF giữ nguyên thứ tự Product trả, không sắp lại, không gọi API
  category/attribute và không tự tra nhãn.
- **Giới hạn:** tối đa 20 media cấp Product và 10 media mỗi SKU, do Product cắt. Thứ tự
  thuộc tính và trục biến thể theo tên (collation tiếng Việt, tie-break `code`). Chưa có
  đơn vị đo; `NUMBER` hiển thị giá trị trần.
- **BFF dựng URL media.** `url` = CDN base URL cấu hình + `storage_key` đã percent-encode
  từng segment. `thumbnail_url` luôn có giá trị: URL dựng từ `thumbnail_storage_key`, hoặc
  URL placeholder cấu hình khi Product trả `null`, cùng quy ước với Search. Public response
  không bao giờ chứa storage key.
- **Kiểm tra tham chiếu.** Ngoài schema, BFF coi các trường hợp sau là response Product sai
  contract (map `503`, tính vào circuit breaker như ADR-004): `media[].sku_id` không có
  trong `skus[]`; giá trị trong `skus[].variant_attributes` không có option tương ứng trong
  `variant_axes`; trùng `sku_id` hoặc `media_id`.
- `catalog_revision` vẫn được trả nhưng không đại diện cho category và nhãn thuộc tính;
  không dùng làm cache key hay version của toàn response.

## Alternatives considered

- **Storefront tự gọi Product/Search lấy category và thuộc tính.** Tạo waterfall sau khi
  resolve `product_id` và đặt quy tắc hiển thị catalog ở frontend.
- **BFF gọi thêm API category/attribute.** Thêm lời gọi vào hot path, cần mở admin API
  cho BFF và để BFF áp quy tắc thứ tự thuộc về Product.
- **Product trả CDN URL.** Mâu thuẫn Product ADR-013 và kéo cấu hình CDN vào Product.
- **`thumbnail_url` nullable, frontend tự chọn placeholder.** Lệch quy ước với Search nên
  storefront phải xử lý hai kiểu thumbnail cho cùng một sản phẩm.

## Consequences

Response lớn hơn nhưng bị chặn trên (tối đa 5 020 media). Payload Product lớn hơn làm tăng
thời gian parse/validate ở BFF; latency p95/p99 của PDP phải được load test lại với
Product nhiều SKU và đủ media. BFF có thêm cấu hình bắt buộc (CDN base URL, URL
placeholder) và readiness phụ thuộc cấu hình đó. Đổi tên category hoặc nhãn thuộc tính có
hiệu lực ở request PDP kế tiếp, vì BFF không cache dài hạn.

## Verification

- Contract test public OpenAPI với fixture Product có category nhiều cấp, `SELECT` và kiểu
  khác, trục biến thể nhiều option, media cấp Product và cấp SKU.
- Test mapping: giữ nguyên thứ tự Product trả; URL dựng đúng và percent-encode; SKU không
  ảnh nhận URL placeholder; không field storage key nào xuất hiện trong public response.
- Test tham chiếu: media trỏ SKU không có, variant value không có option, trùng
  `sku_id`/`media_id` đều trả `503` và ghi `contract_violation`.
- Thiếu CDN base URL hoặc URL placeholder thì BFF không ready.
- Load test với Product 500 SKU và đủ 5 020 media xác nhận SLO ở
  [operations.md](../operations.md#slo).
