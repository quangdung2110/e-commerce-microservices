---
doc_id: checkout-adr-015
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - contracts/http/checkout/v1/openapi.yaml
  - docs/checkout/interfaces.md
  - contracts/http/order/internal/v1/openapi.yaml
---

# ADR-015 — Problem catalog, view model và ánh xạ kết quả Order

## Status

`Accepted` 2026-10-07. Lấp khoảng trống ADR-012 để lại khi thay thế nhánh tính lại voucher
của ADR-006.

## Context

- Checkout OpenAPI chỉ dùng `Problem` chung nên frontend phải đoán `code` từ
  `domain.md` (ghi chú trong `frontend/src/lib/api/problem-messages/commerce.ts`). Domain thiếu
  `ACTIVE_CHECKOUT_CHANGED`, lỗi idempotency, các code do Order trả; còn có code Checkout
  không bao giờ phát ra (`ALLOCATION_CHANGED`).
- Order trả `409 PROMOTION_RECALCULATION_NEEDED` (kèm `dropped_promotions`),
  `INVENTORY_RESERVATION_REJECTED`, `PAYMENT_INITIATION_REJECTED`; recovery read còn có
  `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED`, `ORDER_CANCELLED_DURING_PREPARATION`. Tài liệu Checkout
  chỉ ghi chung "CONFLICT/FAILED".
- Order bắt buộc `snapshot_name`, `snapshot_image_url`, `snapshot_attributes`, `product_id`
  UUID và `final_amount ≥ 1 VND`; Product gRPC trả storage key trong `image_url` và không có
  nhãn biến thể.
- Response Preview lộ `warehouse_id` nội bộ nhưng thiếu giá niêm yết để hiển thị giá gạch.

## Decision

1. **`CheckoutProblem`**: mọi lỗi là RFC 9457 với `code` thuộc enum cố định trong OpenAPI,
   `retryable`, `action` (`RETRY`, `START_NEW_PREVIEW`, `EDIT_CART`, `CHANGE_ADDRESS`,
   `CHANGE_VOUCHER`, `VIEW_SUBMISSION`, `NONE`), `trace_id`, và extension theo code
   (`status_url`, `changed_fields`, `sku_ids`, `rejected_lines`, `promotion_reason`,
   `dropped_promotion`). Bảng đầy đủ ở [interfaces.md](../interfaces.md#error-catalog). Không
   relay body thô của downstream. `400` dùng `INVALID_REQUEST` (gồm field lạ như quantity).
2. **Trạng thái chưa rõ không phải lỗi:** `202` với `state` `PREPARING`/`RESULT_UNKNOWN`.
3. **Kết quả Order** được map tường minh: các code nghiệp vụ đã biết được relay với cùng tên;
   `PROMOTION_RECALCULATION_NEEDED` mang `dropped_promotion` và action `CHANGE_VOUCHER`;
   `409`/`422` lạ thành `SUBMISSION_REJECTED` kèm alert; `400`/`401`/`403` giữ token
   `RETRYABLE` và trả `503`. Checkout không gọi lại Promotion để dựng đề xuất và không tự trừ
   discount.
4. **Revalidation lúc Submit** trả `CART_CHANGED`, `SKU_UNAVAILABLE`, `PRICE_CHANGED`,
   `PROMOTION_CHANGED` theo thứ tự ưu tiên, kèm `changed_fields` đầy đủ.
5. **View model báo giá:** bỏ `warehouse_id`; thêm `list_unit_price` (Product `base_price`);
   `image_url` là URL CDN dựng từ storage key (placeholder khi không có ảnh); `variant_attributes`
   chép nguyên mã → giá trị; `product_id` là UUID. Sang Order: `snapshot_image_url` là cùng URL
   hoặc `null`, `variant_name` không gửi tới khi Product có nhãn.
6. **Tiền:** không phí ship; `final_amount = total_amount − total_discount ≥ 1 VND`, nếu không
   Preview trả `422 FINAL_AMOUNT_NOT_PAYABLE`.

## Alternatives considered

- **Giữ `Problem` chung, liệt kê code trong Markdown:** không sinh được type cho frontend, dễ
  lệch; không chọn.
- **Gọi lại Promotion để dựng `proposal_checkout_data` khi Reserve thất bại (ADR-006 cũ):** lặp
  logic Preview trong nhánh lỗi; Preview mới với khay voucher cho kết quả chính thức; không chọn.
- **Gửi placeholder sang Order khi không có ảnh:** đơn hàng lưu vĩnh viễn một URL không mang
  thông tin; không chọn.

## Consequences

- Frontend chỉ cần switch theo `action`; thông điệp tra theo `code`.
- Thêm code ở Order/Promotion nghĩa là cập nhật enum Checkout (thêm enum là thay đổi cần sinh
  lại type frontend).
- Hiển thị biến thể còn ở dạng mã cho tới khi Product bổ sung nhãn.

## Migration impact

Checkout OpenAPI (proposed) đổi schema lỗi và `PreviewItem`; frontend sinh lại type và cập nhật
`CHECKOUT_MESSAGES`. Order docs ghi rõ hai failure code của recovery read.

## Verification

Contract test mọi response lỗi khớp `CheckoutProblem`; test mapping từng kết quả Order; test
`final_amount < 1`; test không có `warehouse_id` trong response.
