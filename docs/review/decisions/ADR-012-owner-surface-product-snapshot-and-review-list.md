---
doc_id: review-adr-012
service: review
status: accepted
date: 2026-10-06
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/http/review/v1/openapi.yaml
  - contracts/events/order/v1/schemas/order-completed.schema.json
  - docs/review/schema/0002_eligibility_product_snapshot.sql
  - docs/frontend/implementation-plan.md
---

# ADR-012 — Product snapshot trên bề mặt owner và danh sách review của tôi

## Status

`Accepted`. Bổ sung cho [ADR-007](./ADR-007-author-name-snapshot.md): snapshot hiển thị
trên entitlement nay gồm cả tên và thumbnail sản phẩm, không chỉ tên biến thể.

## Context

Frontend (bước 9 của [kế hoạch](../../frontend/implementation-plan.md)) cần hai màn của
buyer: danh sách sản phẩm chờ đánh giá và danh sách review đã viết. Hợp đồng v1 ban đầu
không cho phép dựng hai màn này:

- `PendingEligibility` và `OwnerReview` chỉ có `product_id`, `sku_id` và `variant_name`.
  Buyer không nhận ra mình đang đánh giá sản phẩm nào. Không có API công khai tra
  `product_id` thành tên/ảnh, và Review không được gọi Order hay Product trên đường
  đọc/ghi ([ADR-002](./ADR-002-postgresql-only-read-path.md), WF-REVIEW-001).
- Không có API liệt kê review của chính buyer. `GET /reviews/me/{review_id}` chỉ đọc khi
  đã biết ID, nên một review đã viết (kể cả đã bị ẩn) không có lối quay lại.

## Decision

1. **Snapshot sản phẩm.** `ORDER_COMPLETED.items[]` mang thêm `product_name` và
   `product_image_url` lấy từ snapshot dòng đơn (`OrderLine.snapshot_name`,
   `snapshot_image_url`). Review lưu chúng vào hai cột nullable
   `review_eligibilities.product_name_snapshot` và `product_image_url_snapshot`
   (migration `V2`), cùng nguyên tắc với `variant_name_snapshot`: ghi một lần lúc cấp
   entitlement, không đồng bộ lại, `ON CONFLICT DO NOTHING` nên event phát lại không ghi
   đè. Entitlement cấp trước thay đổi này, và event từ producer cũ, để null.
2. **Chỉ owner thấy.** `OwnerReview` thêm `product_name`, `product_image_url`;
   `PendingEligibility` thêm `order_id`, `product_name`, `product_image_url`.
   `PublicReview` và PDP summary **không** đổi: bề mặt công khai đã có tên/ảnh sản phẩm
   từ Catalog.
3. **`GET /api/v1/reviews/me`.** Liệt kê `OwnerReview` của caller, mới nhất trước, keyset
   `(created_at, review_id)`, `limit ≤ 50`. Gồm cả `HIDDEN_BY_REPORT` và `REMOVED` (owner
   vẫn thấy review của mình), không cấp signed media URL cho review không `APPROVED`,
   không lộ report, legal record hay takedown reason. Owner được tìm qua entitlement
   (`ix_eligibility_owner_erasure`) vì `reviews` không có cột owner; số entitlement của
   một buyer bị chặn bởi số đơn nên sắp xếp trong tập đó rẻ.
4. **Không có `product_slug`.** Order không giữ slug trong snapshot, nên bề mặt owner
   không trả slug và frontend không dựng được link về trang sản phẩm từ màn này. Đó là
   giới hạn có chủ đích; thêm slug phải đi qua Checkout → Order → Review.

## Alternatives considered

- **Frontend tự ghép tên/ảnh từ danh sách đơn của buyer.** Không cần sửa backend, nhưng
  eligibility không trả `order_id` và danh sách đơn chỉ có mặt hàng chính của mỗi đơn,
  nên phải đọc chi tiết từng đơn. Chi phí cao và vỡ khi đơn cũ ra khỏi cửa sổ phân
  trang. Không chọn.
- **Review gọi Order/Product để lấy tên khi đọc.** Vi phạm ADR-002 và tạo phụ thuộc đồng
  bộ trên đường đọc. Không chọn.
- **Chỉ cho mở review qua link thông báo.** Buyer không có cách tự tìm lại review đã
  viết. Không chọn.

## Consequences

- Contract: Order `order-completed` thêm hai field optional (tương thích: consumer
  `additionalProperties`-tolerant); Review OpenAPI thêm `listMyReviews`, thêm field bắt
  buộc-nhưng-nullable vào `OwnerReview`/`PendingEligibility` (response, không phải
  request nên client cũ không vỡ).
- Dữ liệu: tên và URL ảnh sản phẩm lúc mua không phải PII; không nằm trong event Review
  phát ra và không đi vào privacy purge.
- Test: `EntitlementIT` (snapshot, nullable), `ReviewCommandIT` (owner surfaces, danh sách
  của tôi), `CheckoutFlowIT` của Order (payload và schema).
