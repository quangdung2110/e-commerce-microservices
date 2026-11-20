# ADR-005: `Retry-After` bắt buộc trên `429` và `503` có thể retry

- Status: Accepted
- Date: 2026-10-15

## Context

[`conventions/http.md`](../conventions/http.md) chỉ nói `Retry-After` "nên" có khi biết thời gian chờ. Khảo sát
2026-10-15 trên 21 OpenAPI cho thấy 46 trong 186 response `429`/`503` không khai báo header này:

| Contract | Thiếu |
|---|---|
| Auth internal | `429` (2) |
| User | `429` (10) |
| Cart | `503` (4) |
| Notification internal | `503` (5) |
| Payment v1 | `503` (7, kể cả webhook) |
| Payment internal | `503` (9) |
| Search v2 | `503` (9) |

Phần lớn code cũng không gửi: User và Auth gửi `Retry-After` cho `429` nhưng contract không nói; Cart,
Notification, Payment và Search không gửi cho `503`. Caller (Order `HttpRemote`, Checkout, PDP BFF, Notification →
Auth) đã đọc header khi có, nên thiếu nó buộc họ đoán khoảng chờ, và với thời gian chờ đoán sai thì retry dồn
vào đúng lúc provider đang quá tải.

## Decision

1. Mọi response `429` MUST có `Retry-After`. Mọi response `503` có `retryable: true` MUST có `Retry-After`.
   `503` có `retryable: false` (ví dụ `PROVIDER_REQUEST_REJECTED` của Payment) MUST NOT có.
2. Giá trị là số nguyên giây `1`–`3600` (dạng delta-seconds, không dùng HTTP-date). Provider đặt ước lượng tốt nhất
   của mình; khi không có ước lượng thì dùng `2` cho dependency không khả dụng. Rate limiter dùng thời gian còn
   lại của cửa sổ, làm tròn lên.
3. Contract khai báo header trên **mọi** response `429`/`503`, dùng `components/headers/RetryAfter`
   (`type: integer, minimum: 1, maximum: 3600`, `required: false` vì `503` không retry được không mang nó).
   Response dùng chung cho nhiều mã lỗi (ví dụ `Problem` của Payment) phải tách một response riêng cho `503`.
4. Caller coi `Retry-After` là gợi ý, không phải lệnh: thời gian chờ thực = `min(Retry-After, deadline còn lại −
   dự phòng)`, và không vì thế mà thêm retry ở nơi contract cấm retry (mesh §7, `attempts: 0`; app retry theo
   ADR của từng service). Không có header thì dùng backoff cục bộ như trước.
5. Webhook (Stripe → Payment) cũng khai báo: Stripe tự lên lịch retry nên header chỉ mang tính thông tin, nhưng
   quy tắc "mọi `503` retryable" không có ngoại lệ để lint đơn giản.
6. Kiểm tra tự động: `contracts/tools/check_http_contracts.py` (chạy trong CI) fail khi một `429` hoặc `503` trong
   OpenAPI không khai báo `Retry-After`; mỗi provider có test chứng minh code gửi header (xem Verification).

## Alternatives considered

- **Giữ "nên".** Không chọn: 46 chỗ thiếu cho thấy "nên" không đủ để kiểm máy, và lint không thể phân biệt thiếu
  vì quên với thiếu vì không biết.
- **Cho phép HTTP-date.** Không chọn: hai định dạng buộc mọi caller parse cả hai; mọi caller hiện có chỉ đọc số giây.
- **Bắt buộc cả `500`/`502`/`504`.** Không chọn: provider không biết khi nào lỗi không mong đợi hết; mesh và
  gateway tự sinh các mã này mà không có header.

## Consequences

- Cart, Notification, Payment và Search thêm header ở lớp dựng problem; không đổi mã lỗi hay body.
- Contract không đổi nghĩa; thêm header response là tương thích ngược.
- Payment: `503` `retryable=true` (dependency, provider outcome unknown) có header; Order `HttpRemote` đã log
  `Retry-After` nên khoảng chờ xuất hiện trong log mà không cần đổi hành vi.

## Runtime audit (2026-10-16)

Lint contract chỉ chứng minh header được **khai báo**. Load test ([ADR-010](ADR-010-resilience-evidence-fault-injection-and-load-tests.md)) cho thấy ba provider vẫn gửi `503`
retryable không có header vì chỉ đặt nó khi nơi ném lỗi có ước lượng: Product Detail BFF (chỉ khi circuit mở; 0 trên 19 `503` đo được), Order và Inventory. Cả ba nay đặt mặc định `2` giây ở lớp dựng
problem ([PDP ADR-010](../../docs/product-detail-bff/decisions/ADR-010-retry-after-on-every-503.md), [Order ADR-026](../../docs/order/decisions/ADR-026-retry-after-default-on-503.md),
[Inventory ADR-031](../../docs/inventory/decisions/ADR-031-retry-after-default-on-503.md)). Gateway Traefik trả `429` có `Retry-After` (2 394 trên 2 394 ở lần đo). Cart, Payment, Search, Notification và
Order, Inventory, PDP có test trực tiếp cho `503`. Product, Promotion, Review và Checkout đặt header trên mọi mã retryable ở lớp dựng problem (đã đọc code, chưa có test
riêng cho ADR này); User và Auth dùng cùng helper với Notification nhưng chưa kiểm từng lỗi `503` của chúng có cờ retryable.

## Verification

- `check_http_contracts.py` và test của nó (`contracts/tools/tests`): 0 response `429`/`503` thiếu header trên cả 21 OpenAPI;
  test đơn vị chứng minh nó bắt được response thiếu header, kể cả khi đi qua `$ref`.
- Test từng provider: Cart, Notification (Go router test); Payment, Search (MockMvc) kiểm `503` retryable có
  `Retry-After` hợp lệ, `503` không retry được thì không; User, Auth đã có test cho `429`.
