---
doc_id: checkout-domain
service: checkout-service
scope: current
status: implemented
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
verified_against_commit: null
---

# Checkout Service — Domain Model

## Thuật ngữ

| Thuật ngữ | Nghĩa |
|---|---|
| Checkout token | UUIDv4 ngẫu nhiên (CSPRNG) đại diện cho đúng một báo giá; cũng là Order `Idempotency-Key` |
| Báo giá (quotation) | Snapshot server-owned của cart, product, address, allocation, promotion và tổng tiền; bất biến |
| Active pointer | Con trỏ tới token chưa terminal duy nhất của user; con trỏ UX và hàng rào cạnh tranh, không phải nguồn chọn phiên khi submit |
| Fresh preview | Preview không kèm `previous_checkout_token`; thay thế báo giá `PREVIEWED` hiện tại |
| Submit payload | Request Order bất biến được tạo ngay khi token được claim |
| Voucher option | Một voucher trong ví hoặc công khai, đã được Promotion đánh giá trên báo giá; chỉ advisory |
| Submit retry window | 2 phút kể từ claim; chỉ trong cửa sổ này Checkout được gửi lại payload khi Order xác nhận chưa nhận |
| Result | Trạng thái terminal (kết quả hoặc problem) có thể replay |

## Aggregate cục bộ

`CheckoutSession` là aggregate ngắn hạn, định danh bởi `token_hash`:

```text
CheckoutSession
  token_hash, user_id, state, schema_version
  expires_at                     (tạo + 20 phút)
  items_hash, pricing_hash
  address_snapshot               (PII, mã hoá)
  line_items[]                   (sku_id, product_id, quantity, unit_price, list_unit_price,
                                  category_ids, product_name, variant_attributes,
                                  image_storage_key, catalog_revision)
  allocation_plan                (allocation_plan_id, lines[])
  promotion?                     (promotion_id, campaign_revision, discount)
  total_amount, total_discount, final_amount   (VND, integer)
  submission?                    (attempt_id, claimed_at, attempt_started_at,
                                  revalidated_at, last_order_call_started_at)
  created_at, updated_at
```

Dữ liệu chỉ có hiệu lực trong phiên; Checkout không thành owner của Product hay Cart.

## Commands và queries

- `PreviewCheckout(user_id, idempotency_key, address_id, promotion_id?, previous_token?)`
- `ListVoucherOptions(user_id, checkout_token)`
- `SubmitCheckout(user_id, checkout_token)`
- `GetSubmission(user_id, checkout_token)`

## Luật nghiệp vụ

| ID | Luật |
|---|---|
| `CHK-BR-001` | Cart phải có ít nhất một dòng; quantity 1–100; tối đa 50 dòng. |
| `CHK-BR-002` | Mọi SKU phải có trong Product, `SKU_STATUS_ACTIVE`, và `product_id` khớp với Cart. |
| `CHK-BR-003` | Địa chỉ phải thuộc user hiện tại (User xác minh). |
| `CHK-BR-004` | Inventory phải trả `ALLOCATABLE` với plan phủ đúng một dòng cho mỗi `sku_id`; `REJECT_ALL` là hết hàng. |
| `CHK-BR-005` | Tối đa một `promotion_id`. Discount chỉ do Promotion tính; Checkout không tự cộng trừ discount. Promotion `subtotal` phải bằng `total_amount` của Checkout. |
| `CHK-BR-006` | Tiền là integer VND. `line_total = unit_price × quantity`; `total_amount = Σ line_total`; `final_amount = total_amount − total_discount`. Không có phí ship. |
| `CHK-BR-007` | `final_amount` phải ≥ 1 VND vì Payment chỉ tạo attempt dương; nếu không, Preview trả `422 FINAL_AMOUNT_NOT_PAYABLE`. |
| `CHK-BR-008` | Token thuộc đúng user; token của user khác trả `404` để không lộ sự tồn tại. |
| `CHK-BR-009` | Mỗi user có tối đa một token chưa terminal; con trỏ active luôn trỏ tới token đó. |
| `CHK-BR-010` | Fresh preview thay thế token `PREVIEWED` hiện tại; preview kèm `previous_checkout_token` chỉ thành công khi token đó đang là `PREVIEWED` hiện hành. |
| `CHK-BR-011` | Khi token hiện hành đang `SUBMITTING` hoặc `RETRYABLE`, không được tạo báo giá mới (`409 SUBMISSION_IN_PROGRESS`). |
| `CHK-BR-012` | Submit chỉ dùng snapshot/payload gắn với token; claim chỉ thành công khi token `PREVIEWED` và chưa quá `expires_at`. |
| `CHK-BR-013` | Thay đổi vật chất lúc Submit (quantity, saleability, giá bán, `category_ids`, kết quả Promotion) làm token kết thúc `CONFLICT`; tên, ảnh, giá niêm yết đổi không phải thay đổi vật chất. |
| `CHK-BR-014` | Cùng token tạo tối đa một đơn logic: mọi lần gọi Order dùng cùng payload và `Idempotency-Key = checkout_token`. |
| `CHK-BR-015` | Checkout chỉ gửi payload sang Order sau khi revalidation thành công; chỉ gửi lại khi Order xác nhận chưa nhận, không còn lời gọi nào có thể đang bay, và còn trong submit retry window. |
| `CHK-BR-016` | Không có degraded stock hay degraded pricing; `user_ack_degraded_pricing` gửi Order luôn `false`. |
| `CHK-BR-017` | Voucher options chỉ cho token `PREVIEWED` chưa hết hạn; kết quả là advisory, áp voucher luôn qua Preview mới. |
| `CHK-BR-018` | Checkout không ghi vào ví voucher; lưu và nhận voucher là thao tác tường minh của user tại Promotion. |
| `CHK-BR-019` | Khi Preview gặp nhiều vấn đề cùng lúc: lỗi hạ tầng (`503`) thắng mọi lỗi nghiệp vụ; giữa các lỗi nghiệp vụ theo thứ tự `CART_EMPTY` > `SKU_UNAVAILABLE` > `ADDRESS_NOT_FOUND` > `INSUFFICIENT_STOCK` > `PROMOTION_INVALID` > `FINAL_AMOUNT_NOT_PAYABLE`. |
| `CHK-BR-020` | Owner timeout của `SUBMITTING` tính từ `attempt_started_at` (đặt lúc claim và lúc recovery claim), không từ `claimed_at`; `claimed_at` chỉ neo cửa sổ retry 2 phút. |

## State machine

```text
            fresh preview
PREVIEWED ───────────────► SUPERSEDED
    │  quá expires_at (logic, không ghi) ► EXPIRED
    │ claim
    ▼
SUBMITTING ──► SUCCEEDED
    │  ├─────► CONFLICT
    │  ├─────► FAILED
    ▼  │
RETRYABLE ──► SUBMITTING (recovery claim) | SUCCEEDED | CONFLICT | FAILED
```

| State | Chuyển tới | Ý nghĩa | Client thấy |
|---|---|---|---|
| `PREVIEWED` | `SUBMITTING`, `SUPERSEDED`, `EXPIRED` | Báo giá sẵn sàng submit | Báo giá; voucher options |
| `SUBMITTING` | `SUCCEEDED`, `CONFLICT`, `FAILED`, `RETRYABLE` | Đã claim, có payload bất biến | `202 PREPARING` |
| `RETRYABLE` | `SUBMITTING`, `SUCCEEDED`, `CONFLICT`, `FAILED` | Kết quả Order chưa rõ | `202 RESULT_UNKNOWN` |
| `SUCCEEDED` | — | Có `order_id`; replay 24 giờ | `200`/`201` với Payment data mới |
| `CONFLICT` | — | Revalidation phát hiện thay đổi, hoặc Order yêu cầu tính lại voucher | `409` problem |
| `FAILED` | — | Order từ chối/thất bại, hoặc Order xác nhận không nhận submission sau cửa sổ retry | `409` problem |
| `SUPERSEDED` | — | Bị fresh preview thay thế trước khi submit | `410 CHECKOUT_SUPERSEDED` |
| `EXPIRED` | — | Quá `expires_at` khi còn `PREVIEWED` | `410 CHECKOUT_EXPIRED` |

`RETRYABLE` không cho materialize payload mới; mọi retry dùng đúng payload và key cũ.
`SUBMITTING` quá owner timeout không quay về `PREVIEWED`.

## Lỗi domain

Danh mục đầy đủ với HTTP status, `retryable` và `action` ở
[interfaces.md](./interfaces.md#error-catalog). Nhóm chính:

- Dựng báo giá: `CART_EMPTY`, `SKU_UNAVAILABLE`, `ADDRESS_NOT_FOUND`, `INSUFFICIENT_STOCK`,
  `PROMOTION_INVALID`, `FINAL_AMOUNT_NOT_PAYABLE`.
- Cạnh tranh: `ACTIVE_CHECKOUT_CHANGED`, `SUBMISSION_IN_PROGRESS`,
  `CHECKOUT_ALREADY_SUBMITTED`, `IDEMPOTENCY_KEY_REUSED`.
- Vòng đời token: `CHECKOUT_NOT_FOUND`, `SUBMISSION_NOT_FOUND`, `CHECKOUT_EXPIRED`,
  `CHECKOUT_SUPERSEDED`.
- Revalidation lúc Submit: `CART_CHANGED`, `SKU_UNAVAILABLE`, `PRICE_CHANGED`,
  `PROMOTION_CHANGED`.
- Kết quả từ Order: `PROMOTION_RECALCULATION_NEEDED`, `INVENTORY_RESERVATION_REJECTED`,
  `PAYMENT_INITIATION_REJECTED`, `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED`,
  `ORDER_CANCELLED_DURING_PREPARATION`, `SUBMISSION_NOT_ACCEPTED`, `SUBMISSION_REJECTED`.
- Hạ tầng: `CHECKOUT_UNAVAILABLE`, `VOUCHER_OPTIONS_UNAVAILABLE`.
