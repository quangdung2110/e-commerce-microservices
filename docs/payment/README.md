---
doc_id: payment-overview
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence:
  - contracts/http/payment/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/events/payment/internal/v1/asyncapi.yaml
  - contracts/catalog/interactions.yaml
verified_against_commit: null
---

# Payment Service — Tổng quan dịch vụ

> Điểm bắt đầu để hiểu thiết kế mục tiêu của Payment Service. Bộ tài liệu được
> viết lại 2026-09-19 theo ADR-011 → ADR-014, đồng bộ wire contract bằng ADR-015
> ngày 2026-09-20, và không còn giữ
> "thiết kế cũ" như nguồn tham chiếu; mọi hành vi mô tả là **target**, chưa có
> implementation để xác minh. Đọc theo thứ tự ở §9 nếu bạn mới tiếp cận.

## 1. Trạng thái dịch vụ

| Khía cạnh | Trạng thái |
|---|---|
| Thiết kế | `proposed`, scope `target`; 8 tài liệu + 15 ADR đồng bộ với nhau và với Order/Checkout/mesh/gateway |
| Implementation | **Chưa có** mã nguồn, migration, connector, deployment trong repository (`java/services/` chưa có `payment-service`) |
| Contract | [HTTP v1](../../contracts/http/payment/v1/openapi.yaml), [financial events v2](../../contracts/events/payment/v2/asyncapi.yaml) và [internal webhook v1](../../contracts/events/payment/internal/v1/asyncapi.yaml) là contract target; `x-adoption-status: proposed`; chưa có provider/consumer test |
| Runtime đã chốt | Java 21, Spring Boot 3.x, Maven, PostgreSQL, Redis, Kafka, Debezium/Kafka Connect, `stripe-java` ([ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md), [ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)) |
| Provider | Stripe duy nhất, test mode cho toàn bộ dev/demo; Stripe **live** chưa hỗ trợ merchant đăng ký tại Việt Nam (`development.md §6`) |
| Gate lên `implemented` | Code + migration + Debezium connector + contract test + Stripe sandbox webhook chạy end-to-end trong CI |

## 2. Mục tiêu

Payment Service là **authority duy nhất về trạng thái tiền** của một order: tạo
PaymentIntent trên Stripe, nhận và xác thực webhook, chốt kết quả thanh toán, thực
thi đúng một refund trọn đời/order theo lệnh explicit của Order, đối soát định kỳ
với Stripe, và phát financial fact để Order điều phối phần còn lại của saga.

Ưu tiên theo thứ tự: (1) **không sai tiền** — không capture kép, không refund kép,
không terminal non-capture khi intent còn sống; (2) **không mất fact** — webhook đã
nhận `200` thì không bao giờ mất; (3) **không suy luận** — mọi trạng thái đến từ
provider fact hoặc reconciliation, tình huống mơ hồ đi vào review case thay vì tự
quyết; (4) độ trễ.

## 3. Ranh giới trách nhiệm

**Payment chịu trách nhiệm:**

- Sở hữu 9 bảng PostgreSQL (`data.md`) trong một OLTP schema, không partition/cold
  archive ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)).
- Mọi side effect Stripe (`PaymentIntent.create/cancel/retrieve`, `Refund.create/retrieve`)
  chỉ sau khi đã commit một `ProviderOperation` với key bất biến; timeout được
  recover bằng replay cùng key, không bao giờ suy luận non-capture
  ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
- Lưu **duy nhất** `client_secret` (mã hóa); Order đọc qua `client-data` API mỗi lần
  cần relay cho buyer, không giữ bản sao.
- Quyết định khi một attempt chấm dứt: decline là phi terminal; `FAILED`/`EXPIRED`/
  `CANCELED` chỉ sau khi PaymentIntent đã bị hủy
  ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).
- Nhận webhook tại một route public duy nhất, xử lý async qua Kafka, idempotent
  theo `stripe_event_id`, precedence theo `event.created`.
- Chạy worker: Expiration Scanner, Provider-operation Recovery, Refund Status Poller,
  Reconciliation, Webhook Auto-Retry, Manual Webhook Replay, Retention Trimmer; duy trì hàng
  chờ review case có audit cho CSKH/Kế toán.
- Phát 10 financial fact `PAYMENT_*`/`REFUND_*` qua outbox CDC lên `t.payment.events.v2`;
  wire type dùng namespace `com.ecommerce.payment.*.v2`, Kafka value là full
  CloudEvents structured JSON ([ADR-015](./decisions/ADR-015-canonical-asyncapi-and-structured-cloudevents.md)).

**Payment không chịu trách nhiệm:**

- Quyết định *khi nào* một đơn được hoàn tiền: refund chỉ từ explicit command của
  `OrderRefundWorkflow` kèm Inventory evidence ([ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md),
  [ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md)); không consume event Order.
- Phục vụ buyer/CSKH trực tiếp: không có route `/api/payments/**` ngoài webhook;
  buyer đọc payment qua Order.
- Order, inventory, promotion, saga Temporal, UI thanh toán (frontend dùng Stripe
  Elements với `client_secret`), COD hay provider khác Stripe.
- Dispute/chargeback: chưa có FSM; hiện chỉ mở review case (`data.md §7.1`).

Refund là logical module trong Payment, tách service khi refund ratio > 5%
([ADR-002](./decisions/ADR-002-refund-inside-payment-service.md)).

## 4. Vòng đời một khoản thanh toán (tóm tắt)

```
Order initiate ──► INITIATING ──(bind pi_xxx)──► PENDING ──(succeeded)──► SUCCESS ──(refund cmd)──► REFUNDING ──► REFUNDED
                       │                          │  ▲                                                      │
                  recovery/                decline│  │ retry thẻ khác (T3a, không event)                    │ reversal
                  review case                     ▼  │                                                      ▼
                                         cancel intent (CANCEL op) ──► FAILED / EXPIRED / CANCELED        SUCCESS + review case
```

- Mỗi mũi tên đổi trạng thái = một transaction PostgreSQL: aggregate + history +
  outbox (+ review case). Mỗi financial fact tăng `financial_state_version` đúng 1.
- Ba đường vào terminal non-capture đều đi qua cùng một `CANCEL` operation → Order
  nhận `PAYMENT_FAILED/EXPIRED/CANCELED` là được mở attempt kế tiếp ngay.
- Refund: `PENDING → PROCESSING → SUCCESS | FAILED`; `SUCCESS → REVERSED` khi Stripe
  đảo chiều; không bao giờ có refund thứ hai — hoàn tay qua review case.

## 5. Quyền sở hữu dữ liệu

| Nhóm dữ liệu | Nguồn chân lý | Ghi chú |
|---|---|---|
| `payment_transactions`, `refund_transactions` | Payment | Chỉ FSM chuyển trạng thái; `financial_state_version` là contract với Order |
| `provider_operations` | Payment | Bằng chứng mọi side effect Stripe; key bất biến |
| `webhook_logs` | Payment | Raw body nguyên byte; dedup `stripe_event_id` |
| `payment_status_history` | Payment | Audit append-only cho mọi transition kể cả `T3a`/`CORR` |
| `outbox_events` | Payment | Append-only, chỉ Debezium đọc |
| `payment_idempotency` | Payment | Fingerprint + response cho `INITIATE/CANCEL/REFUND` |
| `payment_review_cases`, `reconciliation_runs` | Payment | Hàng chờ thủ công có resolution bắt buộc; metadata đối soát |
| Client data (`client_secret`) | Payment (mã hóa) | Order/Checkout relay, không lưu |
| Redis key | Tạm thời | Replay pointer + lock; mất Redis không đổi kết quả |
| Trạng thái thật trên Stripe | Stripe | Payment replicate, đối soát, mở review case khi lệch |
| Order/inventory/promotion state | Service tương ứng | Payment chỉ giữ `order_id`, `user_id` |

Service khác không ghi DB Payment; Payment không đọc DB service khác.

## 6. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ | Tài liệu |
|---|---|---|
| Khởi tạo thanh toán | `INITIATING` → bind PaymentIntent → `PENDING` + client data; replay idempotent theo attempt | `WF-PAYMENT-001`, `interfaces.md §2.4` |
| Cấp client data | Trả `client_secret` chỉ khi attempt còn `PENDING` và chưa quá hạn | `interfaces.md §2.5` |
| Hủy trước capture | Fact durable `CANCELED`/`ALREADY_NON_CAPTURED`/`ALREADY_SUCCEEDED`/`CANCEL_PENDING` | `WF-PAYMENT-011` |
| Tiếp nhận webhook | `200` < 500ms, async, không mất/không trùng/không sai thứ tự | `WF-PAYMENT-002` |
| Chốt kết quả thanh toán | `SUCCESS`; hoặc `FAILED`/`EXPIRED`/`CANCELED` sau khi intent hủy | `WF-PAYMENT-005`, `-012` |
| Xử lý decline | Giữ `PENDING` qua decline retryable; tự hủy intent khi non-retryable/quá ngưỡng | `WF-PAYMENT-012` |
| Hoàn tiền | Một refund trọn đời/order; Accepted ≠ Final; failed/reversed → review case | `WF-PAYMENT-004`, `-007` |
| Recovery side effect | Replay cùng key; bind hoặc review case `PROVIDER_IDENTITY_UNBOUND` | `WF-PAYMENT-008` |
| Đối soát | Drift → `CORR` khi không freeze, ngược lại review case | `WF-PAYMENT-006` |
| Review case | Case durable với `reason_code`, `evidence`, `resolution_type` bắt buộc | `WF-PAYMENT-010` |
| Tra cứu | Payment + refund duy nhất cho Order verify; read-your-writes | `interfaces.md §2.8` |
| Lưu trữ | Transaction/audit vô thời hạn; trimmer cho outbox/webhook log/idempotency | `data.md §7` |

## 7. Phụ thuộc

| Hướng | Đối tác | Giao thức | Mục đích | Contract / policy |
|---|---|---|---|---|
| Inbound | Order (Temporal Activities) | HTTP/JSON qua mesh mTLS | initiate, cancel, refund, `GET` payment/client-data/refund | [HTTP v1](../../contracts/http/payment/v1/openapi.yaml); `order-initiates-payment`; timeout 15s, mesh không retry |
| Inbound | Stripe | HTTPS webhook qua Gateway `stripe-webhook` | Public `POST /api/payments/webhook/stripe` rewrite sang service `POST /v1/payments/webhook/stripe` — route public duy nhất | [HTTP v1](../../contracts/http/payment/v1/openapi.yaml) ingress + Stripe event schema, API version pin |
| Outbound | Stripe | HTTPS `stripe-java`, egress `ServiceEntry` | PaymentIntent, Refund, verify webhook | `interfaces.md §5` |
| Outbound | Order, Analytics | Kafka `t.payment.events.v2` | 10 financial fact | [events v2](../../contracts/events/payment/v2/asyncapi.yaml); `payment-publishes-payment-events` |
| Internal | Kafka `t.payment.webhooks.raw` | Kafka | Buffer raw webhook exact bytes dạng base64 | [internal webhook v1](../../contracts/events/payment/internal/v1/asyncapi.yaml); 6 partition, retention 7 ngày |
| Internal | PostgreSQL | SQL | 9 bảng source of truth | `data.md` |
| Internal | Redis | Lettuce/Redisson | Replay pointer, lock | `data.md §6`; có fallback DB |
| Internal | Debezium/Kafka Connect | WAL CDC | Outbox → Kafka | [ADR-005](./decisions/ADR-005-transactional-outbox-cdc.md) |
| Internal | Vault/Secret Manager + KMS | — | `sk_`, `whsec_` (2 secret khi rotation), key mã hóa `client_secret` | `operations.md §2`, §7.3 |

## 8. Bất biến quan trọng

Toàn bộ ở [`domain.md §5`](./domain.md#5-bất-biến); bốn bất biến định hình kiến trúc:

- **`INV-PAY-001`** một refund trọn đời/order, amount ≤ tiền đã thanh toán.
- **`INV-PAY-010`** không payment nào vào `FAILED`/`EXPIRED`/`CANCELED` khi PaymentIntent
  còn capture được.
- **`INV-PAY-011`** không side effect Stripe nào thiếu `ProviderOperation` đã commit
  với key bất biến.
- **`INV-PAY-012`** `financial_state_version` tăng đúng 1 cho mỗi financial fact, không
  giảm, không nhảy — contract nhất quán với Order.

Cùng với: verify chữ ký trước mọi transition (`002`), outbox chỉ sau commit (`003`),
ordering theo `order_id` (`004`), audit mọi transition (`005`), dedup theo
`stripe_event_id` (`006`), refund chỉ từ command có evidence (`007`), VND integer
zero-decimal (`008`), không refund thứ hai (`009`), `client_secret` không rời Payment
ngoài response cho Order (`013`).

## 9. Bản đồ tài liệu

Thứ tự đọc gợi ý cho người mới: README → `domain.md` → `workflows.md` → `data.md` →
`interfaces.md` → `architecture.md` → `operations.md` → `development.md`.

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Miền nghiệp vụ](./domain.md) | Thuật ngữ, 4 aggregate, 16 rule, 13 invariant, Payment FSM (`T0`–`T9`, `T3a`, `CORR`), Refund FSM (`R1`–`R5`), policy decline, lỗi miền | Khi thay đổi business logic |
| [Luồng nghiệp vụ](./workflows.md) | 12 workflow `WF-PAYMENT-001`…`012` với TX boundary, race, retry, test scenario | Khi sửa use case |
| [Dữ liệu](./data.md) | DDL 9 bảng, constraint/index, pattern hai transaction, Redis, outbox, retention | Khi sửa persistence |
| [Giao diện](./interfaces.md) | Quy ước HTTP, idempotency, bảng lỗi, 7 endpoint có ví dụ, webhook contract, 10 event + nghĩa vụ consumer, Stripe outbound | Khi tích hợp |
| [Kiến trúc](./architecture.md) | Ranh giới tin cậy, hexagonal, ba pipeline, pattern hai transaction, bốn lớp guard, 12 ràng buộc kiểm tra được | Khi thay đổi cấu trúc/dependency |
| [Vận hành](./operations.md) | SLI/SLO, property `payment.*`, metric `payment_*`, alert, failure mode, deploy/rotation/rollback, 13 runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Build/run/test local, chiến lược test theo tầng Stripe, migration Flyway, fixture, DoD | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | 15 ADR; ADR-009 superseded bởi ADR-013; ADR-015 chốt AsyncAPI/CloudEvents wire contract | Khi đánh giá trade-off |

Tài liệu liên quan ngoài thư mục: `docs/order/data.md §3.5` (attempt mapping, không
lưu client data), `docs/order/interfaces.md §6.1` (consumer event), `docs/mesh/runtime-contract.md`
(AuthorizationPolicy), `docs/gateway/runtime-contract.md` (route webhook),
`contracts/catalog/interactions.yaml`.

## 10. Khoảng trống đã biết và việc tiếp theo

| # | Khoảng trống | Hướng xử lý |
|---|---|---|
| 1 | Không có implementation → mọi `evidence: []`, `verified_against_commit: null` | Gate ở §1 |
| 2 | Dispute/chargeback chưa có FSM/bảng/event; chỉ review case `DISPUTE_OPENED` | ADR riêng + `payment_disputes` (`data.md §7.1`) |
| 3 | GDPR/xóa dữ liệu cho `user_id`, `webhook_logs.raw_body` (có thể chứa `billing_details`) | Policy masking/xóa theo yêu cầu; retention 12 tháng webhook log là điểm bắt đầu |
| 4 | Ngưỡng SLO/alert/capacity là đề xuất, chưa load test | `operations.md §1, §4, §6` phải đo lại trước khi cam kết |
| 5 | Stripe live không hỗ trợ merchant VN | Toàn bộ vòng đời chạy test mode; go-live cần pháp nhân ở nước Stripe hỗ trợ |
| 6 | Chưa có breaking-change check cho OpenAPI/AsyncAPI và consumer test phía Order | Thêm vào CI trước khi `verified` |
