---
doc_id: promotion-interfaces
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/events/product/v1/schemas/product-markdown-snapshot.schema.json
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
verified_against_commit: null
---

# Promotion Service — Interfaces

> HTTP Promotion chưa có OpenAPI. Bảng dưới mô tả semantics đích; wire payload phải
> được chuẩn hóa bằng machine-readable contract trước implementation.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumer | Status |
|---|---|---|---|---|---|
| Admin campaign/voucher/markdown | Inbound | HTTP | Promotion | Admin/Gateway | proposed |
| Calculate/discovery/save | Inbound | HTTP | Promotion | Checkout | proposed |
| Allocation lifecycle | Inbound | HTTP | Promotion | Order/Temporal | proposed |
| Markdown full snapshot | Outbound | Kafka/CDC | Promotion | Product | contract target exists |
| Coupon integration facts | Outbound | Kafka/CDC | Promotion | Notification/Analytics | proposed |
| Claim persistence work item | Internal | Kafka | Promotion API/sweeper | Promotion worker | proposed |
| Welcome voucher | Inbound | Kafka | Auth | Promotion | optional |

Không có inbound `ORDER_REFUNDED`, Promotion refund API hoặc promotion refund event.

## 2. HTTP APIs

User API lấy identity từ token; admin API yêu cầu admin role; allocation API yêu cầu
Order service identity.

| Endpoint | Semantics | Idempotency/lỗi chính |
|---|---|---|
| Admin create/update campaign | Validate `[start,end)`, lifecycle, tăng revision khi update | Optimistic check theo campaign revision |
| Admin publish/pause/cancel campaign | Ghi status/revision; tạo snapshot event khi effective state đổi | Replay cùng expected revision là no-op hoặc conflict rõ |
| Admin create/update voucher/markdown | Chỉ trước publish; validate AST/product; child dùng lịch campaign | Transaction tăng campaign revision |
| `POST /v1/promotions/calculate-discount` | Evaluate 0/1 voucher trên order context; không mutate | Pure/replay-safe |
| `GET /v1/promotions/voucher-suggestions` | Wallet `SAVED`, chưa hết hạn; rule/budget guard | Read-only ngoài lazy expiry |
| `GET /v1/promotions/platform-voucher-discovery` | Wallet + public voucher của campaign ACTIVE | Read-only ngoài lazy expiry |
| `POST /v1/promotions/save-and-apply` | Lưu public voucher, snapshot `expired_at` | Existing SAVED success; USED → 409 |
| `POST /v1/promotions/claim` | Claim limited voucher qua Redis SPOP | `ALREADY_CLAIMED`, `OUT_OF_STOCK` |
| `POST /v1/promotions/reserve` | TRY sau Submit cho 0/1 voucher, hold 15 phút | Replay phải cùng payload |
| `POST /v1/promotions/confirm` | Move reserved→used; wallet `SAVED→USED` | Stable order/promotion key |
| `POST /v1/promotions/cancel` | Release pre-payment reservation; wallet vẫn SAVED | Missing payload cần distinguish no-op/repair |
| `POST /v1/promotions/cancel-confirmed` | Technical compensation sau confirm; wallet về SAVED nếu còn hạn | Durable outbox dedup key theo order/promotion |

Không có `POST /v1/promotions/refund`. Customer refund không gọi Promotion.

Discovery/calculate nhận subtotal toàn đơn, item/category context, payment method và các
field Rule Engine cần. Response trả `eligible`, reason, estimated/actual discount,
`rule_schema_version`, `campaign_revision` và budget-guard metadata. Discount không được
chia thành allocation theo SKU.

## 3. Allocation semantics

- Reserve chỉ hợp lệ với wallet `SAVED`, `expired_at > now` và rule đạt tại Submit.
- Voucher đã lưu có thể reserve sau natural campaign end nếu `expired_at` còn hạn;
  mode `CAMPAIGN_END` đương nhiên hết hạn đúng boundary. Campaign status không phải
  revocation check; emergency revocation chưa thuộc contract này.
- Confirm ghi `used_order_id`, `discount_amount_vnd`, `used_at`.
- Cancel pre-payment chỉ nhả Redis reservation.
- `cancel-confirmed` chỉ dùng khi order không hoàn tất vì lỗi kỹ thuật sau Promotion
  confirm; nó không được dùng cho customer refund.
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

Payload là full snapshot product-scope, gồm tối thiểu:

- `product_id UUID`
- `promotion_rule_id BIGINT`
- `state: ACTIVE | INACTIVE`
- discount data cần để Product reprice
- `source_revision BIGINT`
- effective/reason metadata theo JSON Schema hiện hành

Promotion tính snapshot chiến thắng hiện hành cho product. Event START/END, pause,
cancel hoặc admin edit đều dùng cùng event type; không dùng các event delta
`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`. Product chỉ apply khi
`source_revision` lớn hơn projection hiện tại.

### 4.2 Coupon facts

Sau khi PostgreSQL đã commit, Promotion có thể outbox các integration fact:

| Event logic | Key | Ý nghĩa |
|---|---|---|
| Coupon claimed/saved | user hoặc claim ID | Notification/Analytics; không phải DB persistence trigger |
| Coupon redeemed | order ID | Wallet chuyển USED |
| Coupon technical-cancelled | order ID | Allocation bị đảo do saga failure |
| Budget exhausted | promotion ID | Monitoring/UX signal, không thay synchronous result |

Không có coupon-refunded fact trong Promotion.

## 5. Internal claim work item

Claim Lua commit trên Redis trước PostgreSQL nên pending Hash/ZSET là durable-enough
recovery source của hot path. API publish work item có `claim_id` bất biến; sweeper có
thể republish với delivery ID mới. Worker dùng guarded claim update + unique wallet,
commit DB rồi mới xóa pending. Sau commit, integration fact nếu cần được ghi qua outbox.

Internal work item không được trình bày như business event exactly-once.

## 6. Events tiêu thụ

| Event | Hành động | Guard |
|---|---|---|
| Auth user-registered | Tùy feature flag cấp welcome voucher và snapshot expiry | Unique wallet |
| Internal claim work item | Persist claim + wallet + claimed counter | Immutable claim ID + DB constraints |

`ORDER_REFUNDED` có thể vẫn tồn tại trong Order ecosystem cho Notification/Analytics,
nhưng Promotion không subscribe và payload không cần `promotion_refund_allocations[]`.

## 7. Compatibility

- Unknown optional JSON field phải được chấp nhận; unsupported major version không xử
  lý như v1.
- Markdown contract v1 hiện giữ `promotion_rule_id BIGINT`, product UUID và
  `source_revision BIGINT`; không cần breaking schema change.
- Ordering key của markdown luôn là `product_id`; duplicate/replay là bình thường.
- Claim work item và public integration event phải dùng tên/type khác nhau để tránh một
  consumer vô tình coi Redis acquisition là durable business completion.
- Trước implementation phải bổ sung Promotion OpenAPI/AsyncAPI và contract tests.
