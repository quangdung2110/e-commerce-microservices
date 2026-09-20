---
title: Checkout Domain Model
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
tags: [checkout, domain]
---

# Checkout Domain Model

## Thuật ngữ

| Thuật ngữ | Nghĩa |
|---|---|
| Checkout token | Opaque bearer token đại diện cho đúng một preview snapshot |
| Snapshot | Bản chụp server-owned của cart, product, address, allocation, promotion và totals |
| Active pointer | Con trỏ UX tới preview mới nhất của user; không phải authority của submit |
| Submit payload | Request Order bất biến được materialize khi token được claim |
| Result | Trạng thái/kết quả có thể replay hoặc recovery sau timeout |

## Aggregate cục bộ

`CheckoutSession` là aggregate ngắn hạn, định danh bởi `token_hash`:

```text
CheckoutSession
  token_hash
  user_id
  state
  items_hash
  address_snapshot
  line_items[]
  allocation_plan[]
  promotion_quote?
  subtotal
  discount_total
  total
  currency = VND
  pricing_expires_at
  created_at / updated_at
```

`line_items` lưu ID, quantity, authoritative unit price, product/catalog revision và category IDs cần cho audit. Nó không biến Checkout thành owner của Product; dữ liệu chỉ có hiệu lực trong phiên.

## Commands

- `PreviewCheckout(user_id, address_id, promotion_id?, previous_token?, idempotency_key)`
- `SubmitCheckout(user_id, checkout_token)`
- `GetSubmission(user_id, checkout_token)`

## Luật nghiệp vụ

1. Cart phải tồn tại, không rỗng và mọi quantity hợp lệ.
2. Mọi SKU phải được Product trả về và saleable.
3. Address phải thuộc user hiện tại.
4. Inventory phải trả whole-order allocation plan hợp lệ cho cùng tập item.
5. Promotion tối đa một ID; discount không âm và không vượt subtotal.
6. Totals dùng số nguyên VND và được tính server-side.
7. Token thuộc đúng user; mismatch trả `404` để không tiết lộ sự tồn tại.
8. Submit chỉ dùng snapshot/payload gắn với token; không đọc active pointer để chọn phiên.
9. Cùng token tạo tối đa một logical Order nhờ Order idempotency key ổn định.
10. Active-pointer CAS bảo đảm mỗi user chỉ có một token `PREVIEWED` hiện hành; token đang submit/terminal không bị preview mới xóa.
11. Không có degraded stock hoặc degraded pricing acknowledgement.

## State machine

| State | Có thể chuyển tới | Ý nghĩa |
|---|---|---|
| `PREVIEWED` | `SUBMITTING`, `SUPERSEDED`, `EXPIRED` | Snapshot sẵn sàng submit |
| `SUBMITTING` | `SUCCEEDED`, `CONFLICT`, `RETRYABLE`, `FAILED` | Đã claim và có payload bất biến |
| `RETRYABLE` | `SUBMITTING`, `SUCCEEDED`, `CONFLICT`, `FAILED` | Kết quả trước chưa rõ hoặc dependency tạm lỗi |
| `SUCCEEDED` | — | Có Order ID; replay trong 24 giờ |
| `CONFLICT` | — | Business revision đã đổi; client phải preview lại |
| `FAILED` | — | Lỗi terminal không an toàn để retry cùng snapshot |
| `SUPERSEDED` | — | Preview cũ bị thay trước khi submit |
| `EXPIRED` | — | Snapshot chưa submit hết TTL |

`RETRYABLE` không cho phép materialize payload mới; mọi retry dùng đúng payload/idempotency key cũ.

## Lỗi domain chính

- `CART_EMPTY`, `CART_CHANGED`
- `SKU_UNAVAILABLE`, `PRICE_CHANGED`
- `ADDRESS_NOT_FOUND`
- `INSUFFICIENT_STOCK`, `ALLOCATION_CHANGED`
- `PROMOTION_INVALID`, `PROMOTION_CHANGED`
- `CHECKOUT_SUPERSEDED`, `CHECKOUT_EXPIRED`
- `SUBMISSION_IN_PROGRESS`, `SUBMISSION_RESULT_UNKNOWN`

Các lỗi được biểu diễn bằng RFC 9457 Problem Details; mã ổn định nằm ở trường `code`.
