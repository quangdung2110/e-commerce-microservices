---
doc_id: promotion-interfaces
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
verified_against_commit: null
---

# Promotion Service — Interfaces

> File này bảo toàn semantics trong thiết kế cũ. Theo yêu cầu hiện tại, các contract
> liên-service chưa được chuẩn hóa; payload minh họa không phải nguồn machine-readable.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| Admin promotion management | Inbound | HTTP | Promotion | Admin/Gateway | Chưa có | legacy documented |
| Calculate discount | Inbound | HTTP | Promotion | Checkout | Chưa có | legacy documented |
| Wallet voucher suggestions | Inbound | HTTP | Promotion | Checkout | Chưa có | legacy documented |
| Public platform discovery/save | Inbound | HTTP | Promotion | Checkout | Chưa có | legacy documented |
| Checkout allocation lifecycle | Inbound | HTTP | Promotion | Order/Temporal | Chưa có | legacy documented |
| Promotion events | Outbound | Kafka | Promotion | Product/Notification/Analytics | Chưa có AsyncAPI Promotion | legacy documented |
| Order refund | Inbound | Kafka | Order | Promotion | Chưa đồng bộ ở vòng này | legacy documented |
| User registered | Inbound | Kafka | Auth | Promotion | Chưa đồng bộ ở vòng này | optional |
| Product state changed | Inbound | Kafka | Product | Promotion | Chưa đồng bộ ở vòng này | legacy documented |

## 2. HTTP APIs

Tất cả user API yêu cầu Bearer identity; admin API yêu cầu role admin; allocation API
yêu cầu Order service identity. Deadline/retry/header cụ thể chưa chốt.

| Endpoint | Semantics | Idempotency và lỗi chính |
|---|---|---|
| Admin create/update promotion/markdown | Validate domain/AST, persist, invalidate snapshot, schedule expiry, publish markdown event | Command create/update policy chưa có store idempotency |
| `POST /v1/promotions/calculate-discount` | Evaluate tập promotion trên cart/user context; không mutate quantity | Pure/replay-safe; trả eligibility/reason/discount/rule version |
| `GET /v1/promotions/voucher-suggestions` | Chỉ voucher `WALLET` còn use-window; lazy expiry, rule eval, budget guard | Read-only ngoài lazy state normalization; rate 20/user/min |
| `GET /v1/promotions/platform-voucher-discovery` | Wallet + `PLATFORM_PUBLIC` còn claim-window; group eligible/ineligible | Read-only ngoài lazy expiry; tối đa 20 eligible + 10 ineligible |
| `POST /v1/promotions/save-and-apply` | Có chủ đích lưu public voucher rồi Checkout preview lại | Existing SAVED success; USED → `409 ALREADY_USED_VOUCHER` |
| `POST /v1/promotions/reserve` | TRY sau Submit cho 0/1 promotion; all-or-nothing 15 phút | Replay phải cùng payload; structured budget/mismatch errors |
| `POST /v1/promotions/confirm` | Move reserved→used và commit ledger sau payment | `REDEEM_FINAL:{order}:{promotion}`; missing reservation kích hoạt late-payment path |
| `POST /v1/promotions/cancel` | Release pre-payment reservation, không đổi wallet/usage | `CANCEL_FINAL:{order}:{promotion}` |
| `POST /v1/promotions/cancel-confirmed` | Reverse allocation đã redeemed; validity-aware wallet policy | Cùng scope cancel final nhưng semantics tách khỏi pre-payment cancel |
| `POST /v1/promotions/refund` | Lane sync refund tùy chọn | Full/partial key theo refund request |

Discovery context gồm subtotal/sub-cart subtotal, category IDs, payment method và các
field Rule Engine cần. Thiết kế cũ minh họa `STRIPE` là provider duy nhất. Response trả
`source` (`WALLET`/`PLATFORM_PUBLIC`), `eligible`, reason, `amount_needed`, estimated
discount và cache metadata (`promotion_rule_version`, `promotion_budget_version`, guard
status/time/count). Field contract chính xác sẽ được chốt ở lần refactor contract.

Khi Reserve fail, public reason map về `BUDGET_EXHAUSTED`; Checkout re-evaluate survivors
và trả `409 PROMOTION_RECALCULATION_NEEDED`, `action=RECALL_PREVIEW`, chỉ liệt kê mã bị
drop cùng proposal. Client phải lấy checkout token mới, không reuse token `SUBMITTED`.

## 3. gRPC APIs

Thiết kế hiện tại không định nghĩa gRPC cho Promotion.

## 4. Kafka envelope và events phát ra

Legacy envelope gồm `event_id` UUIDv7, `event_type`, `aggregate_type`, `aggregate_id`,
`correlation_id`, `business_id`, `schema_version=1`, ISO-8601 UTC `timestamp`, `source`
và `payload`. Saga correlation dùng Temporal workflow ID; non-saga dùng request/job ID.
Adapter có thể cần map `timestamp ↔ occurred_at`, `schema_version ↔ event_schema_version`.

Event được direct-produce sau DB commit, best-effort. Consumer phải idempotent.

| Event | Topic / key | Ý nghĩa và consumer |
|---|---|---|
| `MARKDOWN_CREATED` | `t.promotion.markdown.events` / `rule_id` | Rule mới; Product tính promotional price |
| `MARKDOWN_UPDATED` | cùng topic / `rule_id` | Discount/thời gian đổi; Product reprice |
| `MARKDOWN_ENDED` | cùng topic / `rule_id` | Hết hạn/admin disable; reason `EXPIRED`/`ADMIN_DISABLED` |
| `COUPON_CLAIMED` | `t.promotion.coupon.events` / `claim_id` | Claim thành công; claim worker/Notification |
| `COUPON_REDEEMED` | cùng topic / `order_id` | Allocation confirm; Analytics |
| `COUPON_CANCELLED` | cùng topic / `order_id` | Order cancel hoặc reservation TTL; Analytics |
| `COUPON_REFUNDED` | cùng topic / `order_id` | Partial/full refund; Analytics |
| `BUDGET_EXHAUSTED` | `t.promotion.budget.events` / `promotion_id` | Monitoring/UI signal, không thay synchronous reserve result |

`COUPON_CLAIMED.business_id == claim_id`; mỗi publish/republish có `event_id` mới,
`claim_id` bất biến. `production_source` là `API_DIRECT` hoặc `SWEEPER_RESCUE`; consumer
dedup delivery theo event ID và business theo claim ID.

`COUPON_REFUNDED` mang refund transaction, type, discount/refunded amount và
`restore_outcome` (`RETAINED_USED` hoặc `VOUCHER_UNAVAILABLE`). `BUDGET_EXHAUSTED` nêu
quantity/amount exhausted; chi tiết `BUDGET_QTY_EXHAUSTED`/
`BUDGET_AMOUNT_EXHAUSTED` chỉ thuộc synchronous reserve telemetry.

## 5. Events tiêu thụ

| Event | Producer/topic/group | Hành động | Idempotency/ordering/failure |
|---|---|---|---|
| `ORDER_REFUNDED` | Order / `t.order.events` / `promo-order-consumer` | Áp `promotion_refund_allocations[]`, update budget/usage, giữ wallet USED | Envelope event ID + refund transaction scope; key order; payload mismatch → parking-lot + alert |
| `USER_REGISTERED` | Auth / `t.auth.events` / `promo-auth-consumer` | Feature-flag welcome voucher; upsert wallet, expiry +30d | Unique wallet; adapter `data→payload`, `timestamp_ms→registered_at` |
| `PRODUCT_STATE_CHANGED` | Product / `t.product.events` / `promo-product-state-consumer` | Nếu `new_status=ACTIVE`, re-evaluate active markdown và phát lại thay đổi cần thiết | event ID + product ID; trạng thái khác ACK no-op |
| `COUPON_CLAIMED` | Promotion / coupon topic / `promo-claim-consumer` | Guarded claim update + wallet upsert; xóa pending sau commit | event ID + immutable claim ID |

`ORDER_REFUNDED` phải dùng allocation do Order gửi, không suy luận từ returned item.
Partial chỉ restore khi internal PLATFORM rule đạt. Voucher row unavailable là non-
blocking đối với refund tiền.

## 6. Compatibility

- Unknown JSON field phải được consumer chấp nhận khi không phá semantics; unsupported
  version không được xử lý như v1.
- Ordering key giữ ổn định theo bảng event; duplicate/replay là hành vi bình thường.
- Promotion chưa có AsyncAPI/OpenAPI và thiết kế cũ khác contract markdown đích hiện có
  ở Product (legacy category events so với product-scope snapshot/revision). Không chỉnh
  contract ở vòng refactor tài liệu này.
- Trước implementation cần chọn canonical envelope, schema versioning, deprecation
  window, CI breaking-change check và contract tests; lúc đó mới nâng status `verified`.
