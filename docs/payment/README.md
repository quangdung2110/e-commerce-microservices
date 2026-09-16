---
doc_id: payment-overview
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Tổng quan dịch vụ

> Đây là điểm bắt đầu để hiểu thiết kế mục tiêu của Payment Service. Bộ tài liệu
> được tái cấu trúc từ các tài liệu thiết kế Payment cũ (workflow, DB design,
> state machine, events, workers, technology choice và refund boundary ADR); mọi
> quyết định của thiết kế cũ được giữ nguyên, chưa được xác minh với một
> implementation hiện hữu và chưa nhằm chuẩn hóa lại contract liên service.

## 1. Trạng thái dịch vụ

Payment Service đang ở mức thiết kế `proposed`, phạm vi `target`. Chưa tìm thấy mã
nguồn Payment Service, migration, cấu hình Kafka Connect hay deployment trong
repository để chứng minh runtime hoặc hành vi production. Thiết kế đã chốt runtime
Java (Spring Boot 3.x, JDK 21, Maven), PostgreSQL, Redis, Kafka, Debezium/Kafka
Connect và Stripe Java SDK ([ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md),
[ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)); các lựa chọn
này chỉ được xem là đã triển khai khi có evidence tương ứng.

Contract adoption đang ở mức `not_started`: chưa có OpenAPI cho API Payment, chưa
có AsyncAPI/JSON Schema cho `t.payment.events`. `contracts/catalog/interactions.yaml`
ghi hai tương tác `order-initiates-payment` và `payment-publishes-payment-events`
ở trạng thái `legacy_documented`.

## 2. Mục tiêu

Payment Service là nguồn sự thật duy nhất cho trạng thái tiền tệ của một đơn hàng:
tạo giao dịch thanh toán qua Stripe, nhận và xác thực webhook, chốt kết quả
thanh toán, xử lý một refund transaction trọn đời/order theo lệnh explicit của Order, đối soát định kỳ với Stripe và
phát event kết quả để Order Service điều phối phần còn lại của saga.

Service xử lý tiền thật nên ưu tiên tính đúng đắn tài chính trên mọi thứ khác:
PostgreSQL-first với ACID, dual FSM (Payment + Refund) có guard chặt, webhook xử
lý bất đồng bộ nhưng không mất/không trùng, và reconciliation là lớp an toàn bắt
buộc.

## 3. Ranh giới trách nhiệm

Payment Service chịu trách nhiệm:

- Sở hữu `payment_transactions`, `refund_transactions`, `webhook_logs`,
  `payment_status_history`, outbox và idempotency storage của mình.
- Gọi Stripe (`PaymentIntent.create/retrieve`, `Refund.create/retrieve`) và giữ
  toàn bộ chi tiết provider (`pi_xxx`, `re_xxx`, `evt_xxx`, `client_secret`).
- Nhận webhook Stripe tại endpoint cố định, trả 2xx nhanh, xác thực
  `Stripe-Signature` và áp transition FSM một cách idempotent.
- Bảo vệ Payment FSM và Refund FSM, ghi audit trail cho mọi transition.
- Chạy các worker nền: Expiration Scanner, Reconciliation, Refund Status Poller,
  Webhook Auto-Retry, DLQ Replay.
- Phát `PAYMENT_*` và `REFUND_*` qua transactional outbox + CDC lên
  `t.payment.events`.

Payment Service không chịu trách nhiệm:

- Quyết định khi nào một đơn hàng được hoàn tiền. Refund chỉ được khởi tạo bởi
  explicit command từ `OrderRefundWorkflow` với selected-scope inspection completion
  hoặc atomic pre-dispatch release evidence; Payment không suy luận refund từ
  `ORDER_CANCELLED` hay shipping event ([ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md)).
- Sở hữu order, inventory, promotion hay workflow Temporal của Order.
- Thanh toán COD hoặc bất kỳ provider nào khác Stripe (VNPay, MoMo, ZaloPay đã
  bị loại bỏ ở Phase 18).
- Render UI thanh toán; frontend dùng `client_secret` + `publishable_key` với
  Stripe Elements/Checkout SDK, không redirect ra URL ngoài.

Refund là logical module bên trong Payment Service, chưa tách thành service riêng
([ADR-002](./decisions/ADR-002-refund-inside-payment-service.md)). Mỗi local
transaction chỉ bao phủ PostgreSQL của Payment; Stripe API, Redis và Kafka không
cùng transaction với dữ liệu Payment.

## 4. Quyền sở hữu dữ liệu

| Nhóm dữ liệu | Nguồn chân lý | Ghi chú quyền ghi |
|---|---|---|
| `payment_transactions` | Payment Service | Chỉ FSM Payment (API/webhook worker/cronjob) được chuyển trạng thái |
| `refund_transactions` | Payment Service | Chỉ FSM Refund được chuyển trạng thái |
| `webhook_logs` | Payment Service | Raw webhook + kết quả xác thực; append-only theo dedup key |
| `payment_status_history` | Payment Service | Audit trail append-only cho mọi transition |
| `outbox_events` | Payment Service | Append-only để Debezium CDC phát event |
| `payment_idempotency` | Payment Service | Lưu response idempotent cho API command |
| Redis idempotency/lock keys | Guard tạm thời | DB unique constraint và optimistic lock giữ bất biến khi Redis mất |
| Order, inventory, promotion state | Service tương ứng | Payment chỉ giữ `order_id`, `user_id` làm tham chiếu |
| Trạng thái thật trên Stripe | Stripe | Payment replicate qua webhook/poller và đối soát bằng reconciliation |

Service khác không được ghi trực tiếp vào PostgreSQL của Payment. Order Service
đọc trạng thái qua API/event, không truy vấn database Payment.

## 5. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ |
|---|---|
| Khởi tạo thanh toán | Persist create operation `INITIATING`, recover/replay Stripe PaymentIntent, rồi mới tạo `PENDING` + trả `client_secret` |
| Hủy trước capture | Trả durable `CANCELED`/non-capture hoặc `ALREADY_SUCCEEDED` cho Order trước khi Order final-cancel |
| Tiếp nhận webhook Stripe | Nhận raw webhook, trả 2xx < 500ms, xử lý async an toàn với retry/duplicate/out-of-order |
| Chốt kết quả thanh toán | Chuyển `SUCCESS`/`FAILED`/`CANCELED`/`EXPIRED` và phát event tương ứng cho Order |
| Tra cứu giao dịch | Trả chi tiết payment transaction kèm danh sách refund |
| Khởi tạo hoàn tiền | Nhận một explicit command trọn đời/order từ Order, validate unique order + `amount <= paid_amount`, gọi Stripe Refund idempotent |
| Chốt kết quả hoàn tiền | `REFUND_SUCCESS`/`REFUND_FAILED`/`REFUND_REVERSED` từ webhook, poller hoặc reconciliation |
| Hết hạn phiên thanh toán | Đưa transaction quá `expires_at` về `EXPIRED` và phát `PAYMENT_EXPIRED` |
| Đối soát với Stripe | Phát hiện lệch amount/status và phát correction transition |
| Lưu trữ dài hạn | Partition theo tháng, archive > 6 tháng lên S3, tra cứu tiered cho CSKH |

## 6. Phụ thuộc

| Hướng | Service/Hệ thống | Giao thức | Mục đích | Bắt buộc/Tùy chọn | Contract |
|---|---|---|---|---|---|
| Inbound | Order (Temporal Activity `InitiatePaymentActivity`) | HTTP/gRPC | `POST /v1/payments/initiate` | Bắt buộc | Chưa có OpenAPI; `legacy_documented` |
| Inbound | Order (`CancelPaymentActivity`) | HTTP/gRPC | `POST /v1/payments/{transactionId}/cancel` trước final cancellation | Bắt buộc | Proposed semantics trong `interfaces.md` |
| Inbound | Order (`OrderRefundWorkflow`) | HTTP/gRPC | `POST /v1/payments/{transactionId}/refund` | Bắt buộc | Chưa có OpenAPI; `legacy_documented` |
| Inbound | Client/Order | HTTP | `GET /v1/payments/{transactionId}`, `GET /v1/refunds/{refundTransactionId}` (khuyến nghị) | Bắt buộc | Chưa có OpenAPI |
| Inbound | Stripe | HTTPS webhook | `POST /v1/payments/webhook/stripe` | Bắt buộc | Stripe event schema |
| Inbound | Order | Kafka `t.order.events` | `ORDER_CANCELLED` factual reconciliation notification | Bắt buộc | Tài liệu thiết kế hiện hành |
| Outbound | Stripe | HTTPS (`stripe-java`) | PaymentIntent, Refund, BalanceTransaction, Webhook verify | Bắt buộc | Stripe API |
| Outbound | Order, Analytics | Kafka `t.payment.events` | `PAYMENT_*`, `REFUND_*` | Bắt buộc | Chưa có AsyncAPI; `legacy_documented` |
| Internal | Kafka `t.payment.webhooks.raw` | Kafka | Raw webhook buffer cho Webhook Processor | Bắt buộc | Nội bộ Payment |
| Internal | PostgreSQL | SQL | Source of truth, outbox, audit, idempotency | Bắt buộc | Logical design trong `data.md` |
| Internal | Redis | Redis commands | Idempotency pointer và distributed lock | Bắt buộc, có fallback DB | Key/TTL trong `data.md` |
| Internal | Debezium/Kafka Connect | WAL CDC | Relay outbox sang Kafka | Bắt buộc | Cấu hình chưa có evidence |
| Internal | Vault/Secret Manager | — | Stripe API key, `whsec_...` | Bắt buộc | — |
| Internal | S3 (+ Athena hoặc tương đương) | — | Archive partition > 6 tháng, tiered read | Theo thiết kế retention | — |

## 7. Bất biến quan trọng

- [`INV-PAY-001`](./domain.md#5-bất-biến): một refund transaction trọn đời/order;
  amount không vượt tiền đã thanh toán.
- [`INV-PAY-002`](./domain.md#5-bất-biến): không có transition nào bỏ qua guard
  signature/amount/reference; signature verification luôn xảy ra trước state
  transition.
- [`INV-PAY-003`](./domain.md#5-bất-biến): không publish event khi DB transaction
  chưa commit (outbox only-after-commit).
- [`INV-PAY-004`](./domain.md#5-bất-biến): với cùng `order_id`, event payment/refund
  giữ ordering theo partition key `order_id`.
- [`INV-PAY-005`](./domain.md#5-bất-biến): mọi terminal transition đều có audit
  record trong `payment_status_history`.
- [`INV-PAY-006`](./domain.md#5-bất-biến): mọi transition idempotent theo
  `stripe_event_id` hoặc `provider_ref + event_type`; webhook out-of-order không
  override ngược trạng thái terminal hợp lệ.
- [`INV-PAY-007`](./domain.md#5-bất-biến): refund chỉ được khởi tạo từ explicit
  command của Order kèm `Idempotency-Key`, không suy luận từ event.
- Create/cancel provider operation has one durable idempotency identity; uncertain
  transport never authorizes Order to infer payment cancellation.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Kiến trúc](./architecture.md) | Component, Stripe/Kafka/CDC, processing, consistency, concurrency và topology | Khi thay đổi cấu trúc hoặc dependency |
| [Miền nghiệp vụ](./domain.md) | Thuật ngữ, aggregate, Payment/Refund FSM, guard, invariant, lỗi miền | Khi thay đổi business logic |
| [Giao diện](./interfaces.md) | HTTP API, webhook Stripe, event envelope, 9 event phát ra, event tiêu thụ | Khi tích hợp service |
| [Luồng nghiệp vụ](./workflows.md) | Initiate, webhook, query, refund, expiration, reconciliation, poller, cancel | Khi sửa use case |
| [Dữ liệu](./data.md) | Schema logic, constraint, index, transaction, Redis, outbox, partition/archive | Khi sửa persistence |
| [Vận hành](./operations.md) | SLO, config, observability, failure mode, worker, runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Checklist build/test/migration khi có code | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | ADR bảo toàn lý do của thiết kế cũ | Khi đánh giá trade-off |

## 9. Khoảng trống đã biết

- Chưa có implementation, migration, OpenAPI/AsyncAPI, cấu hình Kafka Connect,
  Redis, Stripe webhook endpoint hay deployment để xác minh.
- Tài liệu cũ liên kết tới `docs/_contracts/CONTRACT_MATRIX.md`,
  `OUTBOX_PATTERNS.md` và `CHANGELOG.md` (ID convention, CloudEvents envelope,
  CDC pattern, Phase 16/18/20). Các file này đã bị xóa khỏi repository; nội dung
  được bảo toàn trong bộ tài liệu này nhưng không còn link nguồn.
- Payment FSM `T8` chuyển `REFUNDING -> FAILED` khi refund thất bại, nhưng Refund
  Status Poller mô tả `REFUNDING -> SUCCESS` cho cùng tình huống. Chưa chốt.
- Tên bảng outbox không thống nhất: DB design dùng `outbox_events`, workers dùng
  `payment_outbox`.
- Webhook Auto-Retry Worker dựa vào `retry_count`/`max_retries` trên
  `webhook_logs` nhưng schema chưa khai báo hai cột này.
- Workers cũ có hai mục cùng đánh số "7"; migration từ Outbox Polling sang CDC được
  ghi là đang diễn ra nhưng không có evidence.
- Chưa có SLO đo được ngoài các mục "gợi ý", chưa có capacity benchmark, backup/
  restore, schema rollout hoặc runbook tự động hóa.
