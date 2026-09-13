---
doc_id: promotion-interfaces
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/events/product/v1/schemas/product-markdown-snapshot.schema.json
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
  - contracts/examples/events/promotion-product-markdown-inactive-snapshot.v1.json
verified_against_commit: null
---

# Promotion Service — Interfaces

> HTTP contract chuẩn nằm tại
> [`contracts/http/promotion/v1/openapi.yaml`](../../contracts/http/promotion/v1/openapi.yaml).
> Contract đang ở trạng thái `proposed`; implementation và consumer phải qua contract test
> trước khi chuyển sang `implemented`/`verified`.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumer | Status |
|---|---|---|---|---|---|
| Admin campaign/voucher/markdown | Inbound | HTTP | Promotion | Admin/Gateway | contract target exists |
| Calculate/discovery/save/claim | Inbound | HTTP | Promotion | Checkout/Gateway | contract target exists |
| Allocation lifecycle | Inbound | HTTP | Promotion | Order/Temporal | contract target exists |
| Markdown current-state snapshot | Outbound | Kafka/CDC | Promotion | Product | contract target exists |
| Coupon integration facts | Outbound | Kafka/CDC | Promotion | Notification/Analytics | proposed |
| Welcome voucher | Inbound | Kafka | Auth | Promotion | optional |
| Product tombstone | Inbound | Kafka | Product | Promotion | contract target exists |

Không có inbound `ORDER_REFUNDED`, Promotion refund API hoặc promotion refund event.

## 2. HTTP APIs

User API lấy identity từ token đã được Gateway verify; admin API yêu cầu role `ADMIN`;
`/internal/v1/**` chỉ nhận workload identity allowlist. API dùng JSON snake_case,
`application/problem+json` theo RFC 9457, ID opaque string và tiền theo
`{ amount_minor, currency }` (`currency=VND`). BIGINT/`*_vnd` trong data model chỉ là
storage representation, không phải wire shape.

### 2.1 Admin endpoints

| Method và path | Mục đích | Concurrency/idempotency |
|---|---|---|
| `GET /api/v1/admin/promotion-campaigns` | List campaign theo cursor/status/phase | Safe read |
| `POST /api/v1/admin/promotion-campaigns` | Tạo campaign `DRAFT` | Unique campaign code |
| `GET /api/v1/admin/promotion-campaigns/{campaign_id}` | Đọc campaign và ETag revision | Safe read |
| `PATCH /api/v1/admin/promotion-campaigns/{campaign_id}` | Sửa metadata/lịch khi còn `DRAFT` | Bắt buộc `If-Match` parent revision |
| `POST /api/v1/admin/promotion-campaigns/{campaign_id}/copies` | Clone aggregate thành campaign `DRAFT` mới | Source immutable; `If-Match` source revision |
| `POST /api/v1/admin/promotion-campaigns/{campaign_id}/transitions` | Publish, pause hoặc cancel | `If-Match`; tăng revision đúng một lần |
| `GET/POST /api/v1/admin/promotion-campaigns/{campaign_id}/promotions` | List/tạo voucher definition | Mutation chỉ ở `DRAFT`, tăng parent revision |
| `GET/PATCH /api/v1/admin/promotions/{promotion_id}` | Đọc/sửa voucher definition | PATCH dùng `If-Match` parent campaign |
| `POST /api/v1/admin/promotions/{promotion_id}/grants` | Cấp voucher `DIRECT_GRANT` cho user | Natural key `(user_id,promotion_id)` |
| `GET/POST /api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules` | List/tạo markdown product-scope | Mutation chỉ ở `DRAFT`, tăng parent revision |
| `GET/PATCH /api/v1/admin/markdown-rules/{markdown_rule_id}` | Đọc/sửa markdown rule | PATCH dùng `If-Match` parent campaign |

### 2.2 Customer và Checkout endpoints

| Method và path | Dùng khi nào | Side effect |
|---|---|---|
| `POST /api/v1/me/promotion-voucher-suggestions` | Checkout đã biết user; chỉ xếp hạng voucher `SAVED` trong wallet | Safe query ngoài lazy expiry; `Cache-Control: no-store` |
| `POST /api/v1/promotion-discovery` | Trang checkout muốn tìm cả wallet voucher và public `ACTIVE` voucher chưa lưu | Safe query, không auto-save/reserve; `Cache-Control: no-store` |
| `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | User chọn public voucher và muốn lưu rồi evaluate ngay | Tạo mới `201`; replay wallet `SAVED` trả `200`, không kéo dài expiry |
| `POST /api/v1/me/promotion-voucher-claims` | User claim voucher `LIMITED_CLAIM` | `201` sau DB commit; có thể `202 CLAIM_PENDING` để retry |
| `POST /internal/v1/promotion-discount-calculations` | Checkout server tính lại 0/1 voucher từ trusted context | Pure/replay-safe; không save và không reserve |

Hai query customer dùng POST vì checkout context chứa nhiều item/category và không phù hợp
query string; POST ở đây không đồng nghĩa mutation. `payment_method` hiện bị khóa `STRIPE`
theo Checkout Phase 18. Gateway áp profile `promotion-discovery` 20 request/phút/user cho
`POST /api/v1/promotion-discovery`; Promotion không lưu local rate-limit state.

### 2.3 Order/Temporal endpoints

| Method và path | Pha saga | Kết quả chính |
|---|---|---|
| `POST /internal/v1/promotion-allocation/reservations` | TRY sau Submit, trước payment | Giữ quota/amount 15 phút; `200` replay hoặc `201` tạo mới |
| `POST /internal/v1/promotion-allocation/confirmations` | CONFIRM sau payment success | Commit wallet `USED`, durable budget, receipt và outbox |
| `POST /internal/v1/promotion-allocation/cancellations` | CANCEL khi payment chưa confirm/thất bại | Chỉ nhả reservation `RESERVED`; wallet vẫn `SAVED` |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | Bù kỹ thuật nếu order fail sau Promotion confirm | Đảo durable allocation; wallet về `SAVED` hoặc `EXPIRED` |

Không có promotion refund endpoint. Customer refund không gọi Promotion; technical
cancellation chỉ là compensation của order saga trước khi order hoàn tất.

Discovery/calculate nhận subtotal toàn đơn, item/category context, payment method và các
field Rule Engine cần. Response trả `eligible`, reason, estimated/actual discount,
`rule_schema_version`, `campaign_revision` và budget-guard metadata. Discount không được
chia thành allocation theo SKU.

`campaign_revision` là optimistic concurrency/cache/schedule fence, không phải version ID
của published definition. Sau publish, field ảnh hưởng business là immutable; thay đổi
phải clone campaign DRAFT và child IDs mới. Claim request mang expected revision; Lua chỉ
cho phép acquisition khi claim-gate fields hiện hành trong runtime hash cùng revision và
Redis time nằm trong
`[opens_at,closes_at)`. Claim endpoint preflight durable wallet/claim trước Lua và chỉ trả
success sau PostgreSQL persistence commit; quá HTTP deadline trả `202 CLAIM_PENDING`.

### 2.4 Durable final-command idempotency

Confirm dùng `REDEEM_FINAL:{order_id}:{promotion_id}`; technical compensation dùng
`CANCEL_FINAL:{order_id}:{promotion_id}`. Promotion canonicalize operation, order,
promotion và expected amount thành SHA-256 `request_hash`:

- cùng key + cùng hash → trả stored response từ `promotion_command_receipts`;
- cùng key + khác hash → `IDEMPOTENCY_PAYLOAD_MISMATCH`;
- key khác nhưng cùng `(operation_type,promotion_id,order_id)` → replay cùng business
  outcome hoặc conflict nếu payload khác; không tạo transition thứ hai.

Amount trong request chỉ là expected value. Confirm dùng amount từ matching reservation
`CONFIRMING`; `cancel-confirmed` dùng amount đã persist trên wallet `USED`. Receipt, wallet,
durable budget và outbox commit cùng PostgreSQL transaction. Outbox không phải inbound
command receipt và có retention độc lập.

Confirm luôn lookup receipt theo key và `(operation_type,promotion_id,order_id)` trước
Redis. Nếu đã có durable outcome thì replay không phụ thuộc reservation còn tồn tại. Khi
preflight miss nhưng Confirm-Prepare báo missing, Promotion phải lookup receipt lần nữa để
đóng race với request khác vừa commit/finalize; chỉ khi vẫn không có receipt mới trả
`RESERVATION_NOT_FOUND`. State `CONFIRMING` cùng payload là replay hợp lệ của Prepare.

## 3. Allocation semantics

- Reserve chỉ hợp lệ với wallet `SAVED`, `expired_at > now` và rule đạt tại Submit.
- Voucher đã lưu có thể reserve sau natural campaign end nếu `expired_at` còn hạn;
  mode `CAMPAIGN_END` đương nhiên hết hạn đúng boundary. Campaign status không phải
  revocation check; emergency revocation chưa thuộc contract này.
- Confirm-Prepare chỉ fence `RESERVED→CONFIRMING` và gia hạn grace, chưa move counter.
  PostgreSQL commit ghi receipt, `used_order_id`, `discount_amount_vnd`, `used_at`, budget
  và outbox; Redis finalizer chạy sau commit.
- Cancel pre-payment chỉ nhả Redis reservation đang `RESERVED`; không release mù state
  `CONFIRMING` khi chưa đọc durable confirm outcome.
- `cancel-confirmed` chỉ dùng khi order không hoàn tất vì lỗi kỹ thuật sau Promotion
  confirm; PostgreSQL commit trước và Redis chỉ repair sau. Nó không được dùng cho
  customer refund.
- Partial/full customer refund không có call/event tới Promotion.

## 4. Kafka envelope và publication

Business integration event được insert vào `promotion_outbox_events` cùng transaction
với business state. Debezium/Kafka Connect relay at-least-once. Consumer phải idempotent.
Outbox payload là CloudEvents 1.0 structured JSON hoàn chỉnh; `id` là outbox event ID,
`source=/services/promotion`, time UTC và partition key ổn định.

### 4.1 Product markdown snapshot

Canonical event:

```text
type:  com.ecommerce.promotion.product-markdown-snapshot.v1
topic: t.promotion.markdown.events
key:   product_id
```

Payload chung luôn có `product_id UUID`, `source_revision BIGINT` và
`state: ACTIVE | INACTIVE`. Hai state có shape tách biệt:

- `ACTIVE`: bắt buộc `promotion_rule_id`, discount data, `starts_at`, `ends_at`; không có
  `inactive_reason`.
- `INACTIVE`: bắt buộc `inactive_reason=NO_ACTIVE_RULE|EXPIRED|ADMIN_DISABLED`; không mang
  rule ID, discount hoặc interval.

Promotion tính snapshot chiến thắng hiện hành cho product. Event publish-in-window,
START/END, pause hoặc cancel đều dùng cùng event type; DRAFT edit không phát effective
snapshot. Không dùng các event delta
`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`. Product chỉ apply khi
`source_revision` lớn hơn projection hiện tại. Outbox dedup key gồm product, causal
campaign ID/revision và trigger; resulting state không thuộc key để một cause chỉ phát tối
đa một snapshot. `source_revision` chỉ dùng ordering.

### 4.2 Coupon facts

Trong cùng PostgreSQL transaction với business state, Promotion có thể insert các
integration fact vào outbox; CDC chỉ publish sau khi transaction commit:

| Event logic | Key | Ý nghĩa |
|---|---|---|
| Coupon claimed/saved | user hoặc claim ID | Notification/Analytics; không phải DB persistence trigger |
| Coupon redeemed | order ID | Wallet chuyển USED |
| Coupon technical-cancelled | order ID | Allocation bị đảo do saga failure |

Không có coupon-refunded hoặc budget-exhausted fact trong Promotion. `BUDGET_EXHAUSTED`
chỉ xuất hiện trong discovery/reserve response, metric và log; Redis observation không
được giả làm transactional outbox event.

## 5. Internal claim persistence

Claim Lua commit acquisition trên Redis trước PostgreSQL nên pending Hash/ZSET là recovery
source của crash window, không phải durable success record. API gọi guarded claim update +
unique wallet, insert integration fact vào outbox trong cùng DB transaction và chỉ trả
success sau commit. Sweeper chạy lại cùng persistence function bằng `claim_id` bất biến;
không cần một Kafka business event/topic riêng. Chỉ sau DB commit mới xóa pending Redis.

Pending payload bắt buộc có `acquired_at_ms` lấy từ Redis TIME và campaign revision đã
được claim-gate fields kiểm atomically. Worker validate evidence này nhưng không dùng processing
time để từ chối claim đã linearize trước close boundary.

Nếu DB chưa xác nhận trước HTTP deadline, response là retryable `202 CLAIM_PENDING`; retry
preflight DB rồi pending để resume. Không được trình bày Redis acquisition hoặc internal
task như business event exactly-once.

## 6. Events tiêu thụ

| Event | Hành động | Guard |
|---|---|---|
| Auth user-registered | Tùy feature flag cấp welcome voucher và snapshot expiry | Unique wallet |
| `com.ecommerce.catalog.product-deleted.v1` | Persist terminal Product watermark và invalidate mọi markdown rule; không bump campaign revision/claim-gate fields hoặc emit ngược | Shared product advisory lock, monotonic `catalog_revision`, conditional invalidation |

`ORDER_REFUNDED` có thể vẫn tồn tại trong Order ecosystem cho Notification/Analytics,
nhưng Promotion không subscribe và payload không cần `promotion_refund_allocations[]`.

## 7. Compatibility

- Unknown optional JSON field phải được chấp nhận; unsupported major version không xử
  lý như v1.
- Markdown contract v1 đang proposed và dùng state-discriminated shape. Nếu v1 đã được
  deploy trước thay đổi này, phải tạo v2/dual-read thay vì đổi breaking in-place.
- Ordering key của markdown luôn là `product_id`; duplicate/replay là bình thường.
- Product tombstone consumer chỉ nhận `product-deleted`, persist watermark dù chưa có rule,
  ack sau DB commit và không dùng `promotion_command_receipts` vốn dành riêng final
  synchronous command.
- Không công bố internal claim-persistence task thành integration event; chỉ public claim
  fact sau PostgreSQL commit mới là durable business completion.
- Trước implementation phải bổ sung Promotion-owned AsyncAPI và contract tests; HTTP
  implementation phải sinh/validate theo OpenAPI v1 hiện có.
