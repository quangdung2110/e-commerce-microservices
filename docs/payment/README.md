---
doc_id: payment-overview
service: payment
scope: target
status: implemented
owners:
  - Payment Service team
evidence:
  - contracts/http/payment/v1/openapi.yaml
  - contracts/http/payment/internal/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/events/payment/internal/v1/asyncapi.yaml
  - contracts/catalog/interactions.yaml
  - java/services/payment-service/src/main/resources/db/migration/V1__payment_baseline.sql
  - java/services/payment-service/src/main/resources/kafka/debezium-connector-payment-outbox.json
  - java/services/payment-service/scripts/validate-payment-gate2.ps1
  - java/services/payment-service/src/main/java/com/platform/paymentservice
  - java/services/payment-service/src/test/java/com/platform/paymentservice
verified_against_commit: null
---

# Payment Service — Tổng quan dịch vụ

> Điểm bắt đầu để hiểu thiết kế mục tiêu của Payment Service. Bộ tài liệu được
> viết lại 2026-09-19 theo ADR-011 → ADR-014, đồng bộ wire contract bằng ADR-015
> và khóa Gate 1 bằng ADR-016 → ADR-020, và không còn giữ
> "thiết kế cũ" như nguồn tham chiếu. Gate 3 đã hiện thực runtime và kiểm chứng
> offline trên PostgreSQL 16; Stripe Sandbox E2E vẫn chỉ chạy khi có test-mode secret.
> Đọc theo thứ tự ở §9 nếu bạn mới tiếp cận.

## 1. Trạng thái dịch vụ

Checklist và bằng chứng pass/fail theo từng giai đoạn nằm tại
[implementation-gates.md](./implementation-gates.md).

| Khía cạnh | Trạng thái |
|---|---|
| Thiết kế | `implemented`, scope `target`; tài liệu, ADR, schema và contracts/Order liên quan đồng bộ với Gate 3 runtime |
| Implementation | Spring Boot runtime, FSM, PostgreSQL transaction boundaries, Stripe adapters, webhook pipeline, workers, Finance Ops API, Dockerfile và integration tests tại `java/services/payment-service` |
| Contract | Public/internal HTTP và public/internal AsyncAPI có `x-adoption-status: implemented`; Redocly/AsyncAPI lint + bundle và contract tests chạy được offline |
| Runtime đã chốt | Java 21, Spring Boot 3.x, Maven, PostgreSQL, Kafka, Debezium/Kafka Connect, `stripe-java`; Gate 3 không có Redis adapter ([ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md), [ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)) |
| Provider | Stripe duy nhất, test mode cho toàn bộ dev/demo; Stripe **live** chưa hỗ trợ merchant đăng ký tại Việt Nam (`development.md §6`) |
| External validation | Stripe Sandbox E2E cần test secrets riêng; thiếu secrets phải ghi `NOT RUN`, không ảnh hưởng bằng chứng correctness offline của Gate 3 |

## 2. Mục tiêu

Payment Service là **authority duy nhất về trạng thái tiền** của một order: tạo
PaymentIntent trên Stripe, nhận và xác thực webhook, chốt kết quả thanh toán, thực
thi đúng một refund single-shot trọn đời/order theo lệnh explicit của Order, quản lý
dispute/chargeback và operational subledger, đối soát object + balance với Stripe,
và phát financial fact để Order điều phối phần còn lại của saga.

Ưu tiên theo thứ tự: (1) **không sai tiền** — không capture kép, không refund kép,
không terminal non-capture khi intent còn sống; (2) **không mất fact** — webhook đã
nhận `200` thì không bao giờ mất; (3) **không suy luận** — mọi trạng thái đến từ
provider fact hoặc reconciliation, tình huống mơ hồ đi vào review case thay vì tự
quyết; (4) độ trễ.

## 3. Ranh giới trách nhiệm

**Payment chịu trách nhiệm:**

- Sở hữu 12 bảng PostgreSQL (`data.md`) trong một OLTP schema, không partition/cold
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
- Nhận webhook tại một route public duy nhất; verify exact bytes/signature/300s ngay
  ingress rồi mới durable inbox + Kafka; worker dùng lease/fencing và provider checkpoint.
- Chạy worker: Expiration Scanner, Provider-operation Recovery, Refund Status Poller,
  Reconciliation, Webhook Auto-Retry, Manual Webhook Replay, Retention Trimmer; duy trì hàng
  chờ review case có audit cho CSKH/Kế toán.
- Phát 15 financial fact `PAYMENT_*`/`REFUND_*`/`DISPUTE_*` qua outbox CDC;
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
- General ledger doanh nghiệp và quyết định fulfillment của Order; Payment chỉ sở
  hữu operational subledger và facts liên quan đến tiền.

Refund, dispute và subledger là logical module trong Payment; không tự động tách
thêm service. Thay đổi boundary cần ADR mới (ADR-002, ADR-019).

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
  đảo chiều (hiếm với thẻ, có thể tới ~30 ngày sau); không bao giờ có refund thứ hai —
  hoàn tay qua review case. Trạng thái refund luôn theo Refund object của Stripe
  (`domain.md §6.3`, ADR-022).
- Dispute là FSM orthogonal với funds state riêng; không rewrite Payment FSM. Mọi
  money movement có immutable balanced journal.

## 5. Quyền sở hữu dữ liệu

| Nhóm dữ liệu | Nguồn chân lý | Ghi chú |
|---|---|---|
| `payment_transactions`, `refund_transactions`, `payment_disputes` | Payment | FSM + provider checkpoint; `financial_state_version` là contract với Order |
| `provider_operations` | Payment | Bằng chứng mọi side effect Stripe; key bất biến |
| `webhook_logs` | Payment | Verified encrypted inbox; lease/fencing; dedup event id |
| `financial_journals`, `financial_ledger_entries` | Payment | Immutable double-entry operational subledger |
| `payment_status_history` | Payment | Audit append-only cho mọi transition kể cả `T3a`/`CORR` |
| `outbox_events` | Payment | Append-only, chỉ Debezium đọc |
| `payment_idempotency` | Payment | Fingerprint + response cho buyer/Order command và internal operation |
| `payment_review_cases`, `reconciliation_runs` | Payment | Hàng chờ thủ công có resolution bắt buộc; metadata đối soát |
| Client data (`client_secret`) | Payment (mã hóa) | Order/Checkout relay, không lưu |
| Redis key | Chưa triển khai | Namespace dự trù cho accelerator tương lai; Gate 3 dùng PostgreSQL cho replay/lease/fencing |
| Trạng thái thật trên Stripe | Stripe | Payment replicate, đối soát, mở review case khi lệch |
| Order/inventory/promotion state | Service tương ứng | Payment chỉ giữ `order_id`, `user_id` |

Service khác không ghi DB Payment; Payment không đọc DB service khác.

## 6. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ | Tài liệu |
|---|---|---|
| Khởi tạo thanh toán | `INITIATING` → bind PaymentIntent → `PENDING` + client data; replay idempotent theo attempt | `WF-PAYMENT-001`, `interfaces.md §2.4` |
| Cấp client data | Trả `client_secret` chỉ khi attempt còn `PENDING` và chưa quá hạn | `interfaces.md §2.5` |
| Hủy trước capture | Fact durable `CANCELED`/`ALREADY_NON_CAPTURED`/`ALREADY_SUCCEEDED`/`CANCEL_PENDING` | `WF-PAYMENT-011` |
| Tiếp nhận webhook | Auth + durable accept <500ms; invalid `400`, dependency fail `503`; không mất sau `200` | `WF-PAYMENT-002`, `-009` |
| Chốt kết quả thanh toán | `SUCCESS`; hoặc `FAILED`/`EXPIRED`/`CANCELED` sau khi intent hủy | `WF-PAYMENT-005`, `-012` |
| Xử lý decline | Giữ `PENDING` qua decline retryable; tự hủy intent khi non-retryable/quá ngưỡng | `WF-PAYMENT-012` |
| Hoàn tiền | Một single-shot refund; selected lines full quantity; không partial/incremental | `WF-PAYMENT-004`, `-007` |
| Recovery side effect | Same-key trước horizon; sau horizon lookup-only; DB lease/fencing | `WF-PAYMENT-008` |
| Đối soát | Object-state + balance/fee/payout; drift → correction/review có kiểm soát | `WF-PAYMENT-006`, `-014` |
| Dispute/subledger | Dispute FSM + 5 facts; balanced immutable journals | `WF-PAYMENT-013`, `-014` |
| Review case | Case durable với `reason_code`, `evidence`, `resolution_type` bắt buộc | `WF-PAYMENT-010` |
| Tra cứu | Payment + refund duy nhất cho Order verify; read-your-writes | `interfaces.md §2.8` |
| Lưu trữ/DR | Financial 7 năm, raw webhook 90 ngày, metadata 12 tháng; PITR RPO 0/RTO≤60m | `data.md §7`, ADR-020 |

## 7. Phụ thuộc

| Hướng | Đối tác | Giao thức | Mục đích | Contract / policy |
|---|---|---|---|---|
| Inbound | Order (Temporal Activities) | HTTP/JSON qua mesh mTLS | initiate, cancel, refund, `GET` payment/client-data/refund | [HTTP v1](../../contracts/http/payment/v1/openapi.yaml); `order-initiates-payment`; timeout 15s, mesh không retry |
| Inbound | Stripe | HTTPS webhook qua Gateway `stripe-webhook` | Public `POST /api/payments/webhook/stripe` rewrite sang service `POST /v1/payments/webhook/stripe` — route public duy nhất | [HTTP v1](../../contracts/http/payment/v1/openapi.yaml) ingress + Stripe event schema, API version pin |
| Outbound | Stripe | HTTPS `stripe-java`, egress `ServiceEntry` | PaymentIntent, Refund, Dispute, BalanceTransaction; ingress verify | `interfaces.md §5` |
| Outbound | Order, Analytics | Kafka `t.payment.events.v2` | 15 financial facts | [events v2](../../contracts/events/payment/v2/asyncapi.yaml) |
| Internal | Kafka `t.payment.webhooks.raw` | Kafka | Verified delivery exact bytes base64 | [internal webhook v1](../../contracts/events/payment/internal/v1/asyncapi.yaml) |
| Internal | PostgreSQL | SQL | 12 bảng source of truth | `data.md` |
| Internal ops | HTTP/mTLS | Finance/Accounting Ops | Review/replay/reconcile/freeze/ledger/dispute | [internal OpenAPI](../../contracts/http/payment/internal/v1/openapi.yaml) |
| Internal (dự trù, ngoài Gate 3) | Redis | Chưa có adapter | Có thể thêm replay pointer/lock tối ưu contention; không được tham gia correctness | `data.md §6` |
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

Cùng với: ingress verify trước persist/publish (`002`), outbox chỉ sau commit (`003`),
ordering theo `order_id` (`004`), audit mọi transition (`005`), dedup theo
`stripe_event_id` (`006`), refund chỉ từ command có evidence (`007`), VND integer
zero-decimal (`008`), không refund thứ hai (`009`), `client_secret` không rời Payment
ngoài response cho Order (`013`), ledger cân bằng (`014`) và dispute orthogonal (`015`).

## 9. Bản đồ tài liệu

Thứ tự đọc gợi ý cho người mới: README → `implementation-gates.md` → `domain.md`
→ `workflows.md` → `data.md` → `interfaces.md` → `architecture.md` →
`operations.md` → `development.md`.

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Implementation gates](./implementation-gates.md) | Điều kiện và bằng chứng Gate 1–3 | Trước khi bắt đầu một giai đoạn |
| [Miền nghiệp vụ](./domain.md) | 3 FSM, 22 rule, 15 invariant, leases/checkpoints/ledger/dispute | Khi thay đổi business logic |
| [Luồng nghiệp vụ](./workflows.md) | 14 workflow với TX boundary, race, retry, dispute/subledger | Khi sửa use case |
| [Dữ liệu](./data.md) | DDL 12 bảng, constraints/index, inbox/ledger/retention | Khi sửa persistence |
| [Giao diện](./interfaces.md) | Public/internal HTTP, verified webhook, 15 events, Stripe outbound | Khi tích hợp |
| [Kiến trúc](./architecture.md) | Trust boundaries, pipelines, leases/checkpoints và 18 constraints | Khi thay đổi cấu trúc/dependency |
| [Vận hành](./operations.md) | SLI/SLO, property `payment.*`, metric `payment_*`, alert, failure mode, deploy/rotation/rollback, 13 runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Build/run/test local, chiến lược test theo tầng Stripe, migration Flyway, fixture, DoD | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | 22 ADR; ADR-016…020 khóa Gate 1, ADR-021 pin Stripe contract, ADR-022 củng cố vòng đời refund | Khi đánh giá trade-off |

Tài liệu liên quan ngoài thư mục: `docs/order/data.md §3.5` (attempt mapping, không
lưu client data), `docs/order/interfaces.md §6.1` (consumer event), `docs/mesh/runtime-contract.md`
(AuthorizationPolicy), `docs/gateway/runtime-contract.md` (route webhook),
`contracts/catalog/interactions.yaml`.

## 10. Khoảng trống đã biết và việc tiếp theo

| # | Khoảng trống | Hướng xử lý |
|---|---|---|
| 1 | Correctness runtime Gate 3 đã có; Stripe Sandbox E2E chưa thể chạy nếu máy/CI không nạp test secrets | Chạy checklist `development.md §6.4` trên sandbox riêng trước release và lưu bằng chứng |
| 2 | Kafka broker/Debezium restart và provider-network chaos là kiểm thử môi trường, không nằm trong offline Maven suite | Chạy staging drill trước production; không dùng kết quả giả lập để tuyên bố sandbox pass |
| 3 | SLO/capacity/retention/DR là target chưa được load-test/restore-drill | Đo trước production |
| 4 | Stripe live không hỗ trợ merchant VN | Go-live cần pháp nhân ở nước Stripe hỗ trợ |
| 5 | Validator và semantic lint/bundle đã có nhưng repository chưa gắn chúng vào hosted CI | Gắn `validate_docs.ps1`, `validate-payment-gate2.ps1`, `npm run verify:payment` và `mvn verify` vào CI |
