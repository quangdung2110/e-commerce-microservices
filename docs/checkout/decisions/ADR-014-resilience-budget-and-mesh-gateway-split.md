---
doc_id: checkout-adr-014
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/architecture.md
  - docs/checkout/operations.md
  - docs/mesh/runtime-contract.md
  - docs/gateway/runtime-contract.md
---

# ADR-014 — Ngân sách resilience và phân vai với mesh/Gateway

## Status

`Accepted` 2026-10-07. Bổ sung ADR-011 và ADR-012; không thay thế.

## Context

Tài liệu Checkout chỉ nói "deadline chốt bằng load test", trong khi các con số đã nằm rải rác
ở nơi khác: mesh ghi ngân sách Checkout cho Cart/User 500 ms, Inventory 3 giây, Promotion 2
giây; proto Product đề xuất `GetBatchSkus` 1 giây; Order chờ `PAYMENT_READY` tối đa 12 giây
và mesh cắt ở 15 giây; Gateway chờ business API 25 giây. Thiếu một nơi chốt deadline, timeout,
retry, circuit breaker và việc ai quyết định lỗi thì timeout mesh có thể kích hoạt trước
Checkout, client có thể nhận `504` thay vì problem có kiểm soát, và recovery có thể gửi một báo
giá đã cũ sang Order rất lâu sau khi người dùng bấm đặt hàng.

## Decision

**Deadline theo route** (tính từ lúc Checkout nhận request): Preview 5 000 ms, voucher options
1 500 ms, Submit và status 20 000 ms. Gateway chờ tối đa 25 giây nên luôn còn hơn một giây.

**Wave Preview:** Cart → (User ∥ Product) → (Inventory ∥ Promotion) → Redis. Promotion chạy
song song Inventory vì không cần kết quả Inventory. Trước mỗi wave, deadline còn dưới 200 ms
thì trả `503` mà không gọi.

**Timeout từng dependency** = `min(budget, deadline còn lại − biên)` và luôn ngắn hơn timeout
mesh:

| Dependency | Budget | Mesh |
|---|---:|---:|
| Cart, User | 500 ms | 1 s, 2 s |
| Product `GetBatchSkus` | 1 000 ms | 2 s |
| Inventory allocation preview | 3 000 ms | 4 s |
| Promotion calculation | 2 000 ms | 3 s |
| Promotion voucher options | 1 000 ms | 2 s |
| Order POST | 13 000 ms (Order chờ ≤ 12 s) | 15 s |
| Order GET | 2 000 ms | 15 s |

Order chỉ được gọi khi deadline còn ít nhất 2 000 ms; nếu không, token chuyển `RETRYABLE` và
client nhận `202 RESULT_UNKNOWN`.

**Retry:** ứng dụng không retry trong request. Mesh chỉ retry lỗi kết nối cho Cart, User,
Product. Gửi lại Order chỉ qua luồng recovery, cùng payload và `Idempotency-Key`, khi Order
xác nhận chưa nhận, không còn lời gọi nào có thể đang bay
(`now − last_order_call_started_at ≥ 15 s + 5 s`), và còn trong **submit retry window 2 phút**
kể từ claim. Quá cửa sổ mà Order vẫn `404` → token `FAILED` với `SUBMISSION_NOT_ACCEPTED`.

**Circuit breaker và bulkhead** riêng từng dependency mỗi pod; tham số khởi điểm và công thức ở
[operations.md](../operations.md#resilience). `4xx` nghiệp vụ và `400`/`401`/`403` không tính
vào circuit; `400`/`401`/`403` phát alert cấu hình.

**Hủy request:** Preview và voucher options hủy theo client. Submit sau claim chạy tiếp tới
khi ghi kết quả. Shutdown chờ tối đa 25 giây.

**Phân vai:** Gateway sở hữu JWT, quota theo user (`checkout-preview`, `checkout-submit`,
`checkout-voucher-options`) và timeout edge; mesh sở hữu mTLS, authorization, retry kết nối,
timeout ngoài; Checkout sở hữu deadline, fallback (không có degraded path), circuit/bulkhead
và mapping lỗi. Checkout không có rate limiter riêng.

## Alternatives considered

- **Để mesh quyết định timeout/retry:** mesh không biết dependency nào được phép degrade hay
  POST nào an toàn để retry; không chọn.
- **Retry ứng dụng cho Order POST trong request:** nhân thời gian chờ lên nhiều lần, vượt
  deadline Gateway; recovery qua status an toàn hơn; không chọn.
- **Cho recovery re-POST không giới hạn thời gian:** có thể tạo đơn theo giá cũ hàng chục phút
  sau; không chọn.
- **Rate limiter riêng trong Checkout (key `checkout:rate:*`):** trùng với Gateway và lệch giữa
  replica; không chọn.

## Consequences

- Tích cực: mọi lỗi kết thúc bằng problem có kiểm soát; không có retry storm; recovery có
  giới hạn rõ.
- Tiêu cực: worst-case Preview gần 5 giây khi dependency chậm sát budget; cần load test để siết.
- Constraint: đổi bất kỳ con số nào phải cập nhật ADR này, operations, mesh và Gateway cùng
  lúc và giữ hai bất đẳng thức timeout Checkout < mesh, deadline < Gateway − 1 s.

## Migration impact

Mesh §7 thêm dòng voucher options và ghi rõ timeout Checkout cho Order; Gateway thêm profile
`checkout-voucher-options` và ghi chú deadline Checkout. Key `checkout:rate:*` bị bỏ khỏi
data design.

## Verification

Test resilience ở [development.md](../development.md#test-bắt-buộc); load test xác nhận SLO; dashboard
`checkout_dependency_calls_seconds` không có `timeout` do mesh cắt trước Checkout.
