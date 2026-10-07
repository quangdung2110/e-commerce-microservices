---
doc_id: checkout-workflows
service: checkout-service
scope: target
status: proposed
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
  - ../../contracts/http/promotion/v1/openapi.yaml
verified_against_commit: null
---

# Checkout Service — Workflows

Timeout và deadline trong các bước là giá trị của
[ADR-014](./decisions/ADR-014-resilience-budget-and-mesh-gateway-split.md); mapping chi tiết
ở [interfaces.md](./interfaces.md).

## WF-01 Preview

1. Gateway xác thực, áp profile `checkout-preview` và inject `X-User-Id`. Checkout đặt
   deadline 5 000 ms, chuẩn hoá request ID, validate body và `Idempotency-Key`.
2. Nếu replay idempotency còn hạn: cùng fingerprint thì trả lại response đã lưu; khác thì
   `409 IDEMPOTENCY_KEY_REUSED`.
3. Đọc con trỏ active: token hiện hành đang `SUBMITTING`/`RETRYABLE` thì trả
   `409 SUBMISSION_IN_PROGRESS` kèm `status_url`, không gọi dependency nào.
4. Wave 1: Cart checkout snapshot. Rỗng → `422 CART_EMPTY`. Tính `items_hash`.
5. Wave 2 song song: User address snapshot và Product `GetBatchSkus`. Áp
   [mapping Cart + Product](./interfaces.md#mapping-cart--product--báo-giá); tính
   `total_amount`.
6. Wave 3 song song: Inventory allocation preview và (nếu có `promotion_id`) Promotion
   calculation. `REJECT_ALL` → `422 INSUFFICIENT_STOCK`; `eligible=false` →
   `422 PROMOTION_INVALID`. Không có `promotion_id` thì discount 0, revision 0.
7. Tính `final_amount`; dưới 1 VND → `422 FINAL_AMOUNT_NOT_PAYABLE`. Tính `pricing_hash`.
8. Sinh token UUIDv4 bằng CSPRNG; key Redis chỉ dùng HMAC token hash.
9. Wave 4, một Lua script: kiểm tra lại không có token `SUBMITTING`/`RETRYABLE`; nếu request
   có `previous_checkout_token` thì nó phải là token `PREVIEWED` hiện hành, sai thì
   `409 ACTIVE_CHECKOUT_CHANGED`; ghi session mới, chuyển token `PREVIEWED` hiện hành (nếu có)
   sang `SUPERSEDED`, cập nhật con trỏ active và ghi replay idempotency. Script thất bại
   không để lại session mồ côi.
10. Trả `200` với `Cache-Control: private, no-store`.

Bất kỳ lỗi hạ tầng, timeout, circuit mở, bulkhead đầy hay hết deadline ở bước 4–9 đều trả
`503 CHECKOUT_UNAVAILABLE` và không đổi state. Client hủy request thì mọi lời gọi bị hủy.

## WF-02 Submit lần đầu

1. Gateway áp profile `checkout-submit`. Checkout đặt deadline 20 000 ms, validate token, tính
   token hash.
2. Token `PREVIEWED` còn hạn mà circuit Order đang mở → `503 CHECKOUT_UNAVAILABLE`, chưa claim
   (replay một kết quả terminal không cần Order nên không bị chặn).
3. Lua đọc session và kiểm tra owner (sai user → `404 CHECKOUT_NOT_FOUND`):
   - `SUPERSEDED` → `410 CHECKOUT_SUPERSEDED`; `PREVIEWED` quá `expires_at` →
     `410 CHECKOUT_EXPIRED`;
   - `SUBMITTING`/`RETRYABLE` → [WF-03](#wf-03-submit-trùng-hoặc-đồng-thời); terminal → replay;
   - `PREVIEWED` còn hạn → atomically chuyển `SUBMITTING`, ghi `attempt_id`, `claimed_at`,
     payload Order bất biến (mã hoá) và gia hạn TTL recovery 30 phút.
4. Từ đây phần việc tách khỏi client cancellation. Revalidate song song Cart (`items_hash`) và
   Product (saleability, `price`, `category_ids`), sau đó Promotion (nếu có voucher) với giá
   hiện tại. Inventory không revalidate: Order Reserve kiểm tra đúng plan.
5. Có thay đổi vật chất → ghi `CONFLICT` với code theo thứ tự ưu tiên và `changed_fields`
   đầy đủ; trả `409`. Lỗi hạ tầng khi revalidate → chuyển `RETRYABLE` (chưa có
   `revalidated_at`, chưa gọi Order) và trả `503 CHECKOUT_UNAVAILABLE`; client gửi lại Submit
   cùng token để recovery revalidate rồi mới gọi Order.
6. Không đổi: ghi `revalidated_at`. Deadline còn ≥ 2 000 ms thì ghi
   `last_order_call_started_at` và gọi Order với payload/key đã lưu; còn ít hơn thì chuyển
   `RETRYABLE` và trả `202 RESULT_UNKNOWN`.
7. Ghi kết quả theo [mapping kết quả Order](./interfaces.md#mapping-kết-quả-order):
   thành công lưu `SUCCEEDED` + `order_id` 24 giờ và trả `201` với Payment data vừa nhận
   (không lưu secret); kết quả không rõ chuyển `RETRYABLE` và trả `202 RESULT_UNKNOWN`.

## WF-03 Submit trùng hoặc đồng thời

- `SUCCEEDED`: gọi Order GET để lấy Payment data mới rồi trả `200` cùng `order_id`; Order GET
  lỗi thì trả `202 RESULT_UNKNOWN` (không trả secret cũ vì không có).
- `CONFLICT`/`FAILED`: replay đúng problem đã lưu.
- `SUBMITTING` chưa quá owner timeout (30 giây tính từ `attempt_started_at`): `202 PREPARING` kèm
  `Retry-After`, không hỏi Order.
- `SUBMITTING` quá owner timeout, hoặc `RETRYABLE`: chạy [WF-04](#wf-04-recovery-sau-kết-quả-không-rõ).
- `SUPERSEDED`/`EXPIRED` chưa từng submit: `410`.

Không request nào giữ lock trong lúc chờ network. An toàn đến từ Lua/CAS cộng Order
idempotency.

## WF-04 Recovery sau kết quả không rõ

Kích hoạt bởi `GET /api/v1/checkout/submissions/{checkout_token}` hoặc Submit trùng; không có
worker nền.

1. Đọc kết quả local; terminal thì trả ngay (với `SUCCEEDED` lấy Payment data mới từ Order).
   Token `SUBMITTING` chưa quá owner timeout là attempt đang chạy của request khác: `202
   PREPARING`, không hỏi Order. Token `PREVIEWED`, `SUPERSEDED` hoặc đã quá hạn là chưa từng submit:
   `404 SUBMISSION_NOT_FOUND`.
2. Gọi Order `GET /internal/v1/checkout-order-submissions/{checkout_token}` và map theo
   [bảng](./interfaces.md#mapping-kết-quả-order).
3. Order trả `404`:
   - nếu `now − last_order_call_started_at < 15 giây + 5 giây` (một lời gọi trước còn có thể
     đang bay): trả `202 RESULT_UNKNOWN`, không gửi lại;
   - nếu còn trong submit retry window (2 phút kể từ `claimed_at`): CAS claim recovery
     (`RETRYABLE`/`SUBMITTING` quá hạn → `SUBMITTING`); nếu chưa có `revalidated_at` thì chạy
     lại revalidation của WF-02 bước 4–5 (có thể kết thúc `CONFLICT`); sau đó ghi
     `last_order_call_started_at`, POST lại **đúng** payload và `Idempotency-Key`, rồi map kết
     quả như WF-02 bước 7;
   - nếu đã qua cửa sổ retry: ghi `FAILED` với `SUBMISSION_NOT_ACCEPTED` và trả `409`.
     Order đã xác nhận không có submission và không lời gọi nào còn có thể tới Order, nên
     người dùng an toàn để tạo báo giá mới.
4. Order GET lỗi hạ tầng: giữ nguyên state, trả `202 RESULT_UNKNOWN` kèm `Retry-After`; không
   bao giờ trả `410` hay khuyên tạo báo giá mới khi chưa phân giải.

Giới hạn cửa sổ retry ngăn gửi một báo giá đã cũ sang Order quá lâu sau khi user bấm đặt
hàng. Rủi ro còn lại (Order nhận request nhưng chưa ghi nhận sau hơn 2 phút) được chấp nhận
và có alert ở [operations.md](./operations.md#alert).

## WF-05 Preview khi submission đang chạy

Preview bị từ chối với `409 SUBMISSION_IN_PROGRESS` kèm `status_url` khi token hiện hành của
user đang `SUBMITTING` hoặc `RETRYABLE`. Frontend chuyển sang màn trạng thái. Khi token đó
terminal (`SUCCEEDED`, `CONFLICT`, `FAILED`), Preview lại được phép. Cách này ngăn tạo hai đơn
từ hai token khi người dùng tải lại trang giữa lúc đặt hàng
([ADR-016](./decisions/ADR-016-fresh-preview-and-in-flight-submission-guard.md)).

## WF-06 Promotion thay đổi

- Lúc Preview: voucher không đủ điều kiện → `422 PROMOTION_INVALID`; báo giá cũ không đổi.
- Lúc Submit: revalidation khác báo giá → `409 PROMOTION_CHANGED`.
- Lúc Order Reserve: `409 PROMOTION_RECALCULATION_NEEDED` kèm `dropped_promotion`.

Trong cả ba trường hợp Checkout không tự trừ discount; frontend mở khay voucher hoặc bỏ
voucher rồi Preview lại.

## WF-07 Tải lại trang hoặc mở tab mới

1. Frontend chưa có token: gọi Preview **không** kèm `previous_checkout_token` với địa chỉ mặc
   định (từ User API) và voucher người dùng đã chọn nếu còn nhớ ở phía client.
2. `200`: báo giá mới thay báo giá cũ; tab cũ bấm đặt hàng sẽ nhận `410 CHECKOUT_SUPERSEDED`.
3. `409 SUBMISSION_IN_PROGRESS`: chuyển tới `status_url`.

Mỗi lần tải trang là một `Idempotency-Key` mới.

## WF-08 Khay voucher

1. Có token `PREVIEWED`, frontend gọi
   `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` (profile
   `checkout-voucher-options`).
2. Checkout đọc session, dựng trusted context từ snapshot và gọi Promotion voucher options.
   Token không còn `PREVIEWED` → `410`/`409` theo error catalog; Promotion lỗi →
   `503 VOUCHER_OPTIONS_UNAVAILABLE` (frontend ẩn khay, vẫn cho đặt hàng không voucher).
3. Người dùng chọn một voucher theo `required_action`:
   - `NONE`: Preview kèm `previous` và `promotion_id`;
   - `SAVE`: `PUT /api/v1/me/promotion-vouchers/{promotion_id}` với body `{}`, rồi Preview;
   - `CLAIM`: `POST /api/v1/me/promotion-voucher-claims` với `promotion_id` và
     `expected_campaign_revision`; `202 CLAIM_PENDING` thì retry claim theo Promotion, xong
     mới Preview.
4. Bỏ voucher: Preview kèm `previous` và `promotion_id=null`.

Khay voucher không bao giờ đổi state của báo giá; chỉ Preview mới tạo báo giá có voucher.
