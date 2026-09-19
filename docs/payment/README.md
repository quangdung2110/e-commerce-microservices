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
> implementation hiện hữu. Payment HTTP v1 và events v2 bên dưới là contract target
> liên service; chúng vẫn cần provider/consumer test trước khi được xem là deployed.

## 1. Trạng thái dịch vụ

Payment Service đang ở mức thiết kế `proposed`, phạm vi `target`. Chưa tìm thấy mã
nguồn Payment Service, migration, cấu hình Kafka Connect hay deployment trong
repository để chứng minh runtime hoặc hành vi production. Thiết kế đã chốt runtime
Java (Spring Boot 3.x, JDK 21, Maven), PostgreSQL, Redis, Kafka, Debezium/Kafka
Connect và Stripe Java SDK ([ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md),
[ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)); các lựa chọn
này chỉ được xem là đã triển khai khi có evidence tương ứng.

Contract chuẩn đã được chốt tại [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml)
và [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml). Việc implementation,
consumer contract test và catalog adoption vẫn là production gate.

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

- Sở hữu `payment_transactions`, `provider_operations`, `refund_transactions`,
  `webhook_logs`, `payment_status_history`, `payment_review_cases`,
  `reconciliation_runs`, outbox và idempotency storage của mình — một OLTP schema,
  không partition/cold archive ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)).
- Gọi Stripe (`PaymentIntent.create/retrieve/cancel`, `Refund.create/retrieve`) sau
  khi đã commit một `ProviderOperation` với key bất biến, và giữ toàn bộ chi tiết
  provider (`pi_xxx`, `ch_xxx`, `re_xxx`, `evt_xxx`). Payment là nơi lưu **duy nhất**
  của `client_secret` (mã hóa); Order đọc qua `client-data` API khi cần relay cho buyer.
- Quyết định khi nào một attempt chấm dứt: decline là phi terminal; `FAILED`/`EXPIRED`/
  `CANCELED` chỉ sau khi PaymentIntent đã bị hủy
  ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).
- Nhận webhook Stripe tại endpoint cố định, trả 2xx nhanh, xác thực
  `Stripe-Signature` và áp transition FSM một cách idempotent.
- Bảo vệ Payment FSM và Refund FSM, ghi audit trail cho mọi transition.
- Chạy các worker nền: Expiration Scanner, Provider-operation Recovery,
  Reconciliation, Refund Status Poller, Webhook Auto-Retry, DLQ Replay, Retention
  Trimmer; duy trì hàng chờ review case cho CSKH/Kế toán.
- Phát `PAYMENT_*` và `REFUND_*` qua transactional outbox + CDC lên
  `t.payment.events.v2`.

Payment Service không chịu trách nhiệm:

- Quyết định khi nào một đơn hàng được hoàn tiền. Refund chỉ được khởi tạo bởi
  explicit command từ `OrderRefundWorkflow` với selected-scope inspection completion
  hoặc atomic pre-dispatch release evidence; Payment không suy luận refund từ
  Order/shipping event ([ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md)).
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
| `payment_transactions` | Payment Service | Chỉ FSM Payment (API/webhook worker/scheduler/reconciliation) được chuyển trạng thái; lưu client data mã hóa |
| `provider_operations` | Payment Service | Side effect Stripe durable; commit trước mọi call; key bất biến |
| `refund_transactions` | Payment Service | Chỉ FSM Refund được chuyển trạng thái |
| `webhook_logs` | Payment Service | Raw webhook nguyên byte + kết quả xác thực; dedup `stripe_event_id` |
| `payment_status_history` | Payment Service | Audit trail append-only cho mọi transition |
| `outbox_events` | Payment Service | Append-only để Debezium CDC phát event |
| `payment_idempotency` | Payment Service | Fingerprint + response idempotent cho `INITIATE`/`CANCEL`/`REFUND` |
| `payment_review_cases`, `reconciliation_runs` | Payment Service | Hàng chờ xử lý thủ công có audit; metadata đối soát |
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
| Tra cứu giao dịch | Trả chi tiết payment transaction kèm refund; trả client data cho Order relay khi attempt còn `PENDING` |
| Khởi tạo hoàn tiền | Nhận một explicit command trọn đời/order từ Order, validate unique order + `amount <= paid_amount`, gọi Stripe Refund idempotent |
| Chốt kết quả hoàn tiền | `REFUND_SUCCESS`/`REFUND_FAILED`/`REFUND_REVERSED` từ webhook, poller hoặc reconciliation |
| Hết hạn phiên thanh toán | Hủy PaymentIntent rồi đưa transaction quá `expires_at` về `EXPIRED` và phát `PAYMENT_EXPIRED` |
| Xử lý decline | Giữ attempt `PENDING` qua decline retryable; tự hủy intent và `FAILED` khi decline non-retryable/quá ngưỡng |
| Đối soát với Stripe | Phát hiện lệch amount/status, phát correction transition khi không freeze, mở review case khi cần |
| Review case | Một case durable cho mọi tình huống không được tự quyết (refund failed/reversed, capture-after-cancel, drift, dispute) với resolution bắt buộc |
| Lưu trữ | Một OLTP schema, giữ transaction/audit vô thời hạn; trimmer cho outbox/webhook log/idempotency |

## 6. Phụ thuộc

| Hướng | Service/Hệ thống | Giao thức | Mục đích | Bắt buộc/Tùy chọn | Contract |
|---|---|---|---|---|---|
| Inbound | Order (Temporal Activities) | HTTP | Initiate/cancel/refund/query | Bắt buộc | [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml); buyer/CSKH reads go through Order, never direct Payment |
| Inbound | Stripe | HTTPS webhook | `POST /v1/payments/webhook/stripe` | Bắt buộc | Stripe event schema |
| Outbound | Stripe | HTTPS (`stripe-java`) | PaymentIntent (create/retrieve/cancel), Refund (create/retrieve), Webhook verify | Bắt buộc | Stripe API |
| Outbound | Order, Analytics | Kafka `t.payment.events.v2` | `PAYMENT_*`, `REFUND_*` | Bắt buộc | [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml) |
| Internal | Kafka `t.payment.webhooks.raw` | Kafka | Raw webhook buffer cho Webhook Processor | Bắt buộc | Nội bộ Payment |
| Internal | PostgreSQL | SQL | Source of truth, outbox, audit, idempotency | Bắt buộc | Logical design trong `data.md` |
| Internal | Redis | Redis commands | Idempotency pointer và distributed lock | Bắt buộc, có fallback DB | Key/TTL trong `data.md` |
| Internal | Debezium/Kafka Connect | WAL CDC | Relay outbox sang Kafka | Bắt buộc | Cấu hình chưa có evidence |
| Internal | Vault/Secret Manager + KMS | — | Stripe API key, `whsec_...`, key mã hóa `client_secret_ciphertext` | Bắt buộc | — |

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
  `stripe_event_id`; webhook out-of-order không override ngược trạng thái terminal
  hợp lệ.
- [`INV-PAY-007`](./domain.md#5-bất-biến): refund chỉ được khởi tạo từ explicit
  command của Order kèm `Idempotency-Key`, không suy luận từ event.
- [`INV-PAY-010`](./domain.md#5-bất-biến): không payment nào vào `FAILED`/`EXPIRED`/
  `CANCELED` khi PaymentIntent còn capture được.
- [`INV-PAY-011`](./domain.md#5-bất-biến): không side effect Stripe nào thiếu
  `ProviderOperation` đã commit với key bất biến; uncertain transport never authorizes
  Order to infer payment cancellation.
- [`INV-PAY-013`](./domain.md#5-bất-biến): `client_secret` không rời Payment ngoài
  response initiate/client-data cho Order workload.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Kiến trúc](./architecture.md) | Ranh giới tin cậy, cấu trúc hexagonal, ba pipeline, pattern hai transaction, bốn lớp guard, ràng buộc kiểm tra được | Khi thay đổi cấu trúc hoặc dependency |
| [Miền nghiệp vụ](./domain.md) | Thuật ngữ, aggregate, Payment/Refund FSM, guard, invariant, lỗi miền | Khi thay đổi business logic |
| [Giao diện](./interfaces.md) | Quy ước HTTP, idempotency, bảng lỗi, 7 endpoint có ví dụ, webhook contract, 10 event + nghĩa vụ consumer, contract Stripe outbound | Khi tích hợp service |
| [Luồng nghiệp vụ](./workflows.md) | Initiate, webhook, query/client-data, refund, expiration, reconciliation, poller, recovery, retry, review case, cancel, decline | Khi sửa use case |
| [Dữ liệu](./data.md) | DDL 9 bảng, constraint, index, transaction, Redis, outbox, retention | Khi sửa persistence |
| [Vận hành](./operations.md) | SLO, config, observability, failure mode, worker, runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Checklist build/test/migration khi có code | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | ADR bảo toàn lý do của thiết kế cũ | Khi đánh giá trade-off |

## 9. Khoảng trống đã biết

- Chưa có implementation, migration, cấu hình Kafka Connect,
  Redis, Stripe webhook endpoint hay deployment để xác minh.
- Tài liệu cũ liên kết tới `docs/_contracts/CONTRACT_MATRIX.md`,
  `OUTBOX_PATTERNS.md` và `CHANGELOG.md` (ID convention, CloudEvents envelope,
  CDC pattern, Phase 16/18/20). Các file này đã bị xóa khỏi repository; nội dung
  được bảo toàn trong bộ tài liệu này nhưng không còn link nguồn.
- Workers cũ có hai mục cùng đánh số "7"; migration từ Outbox Polling sang CDC được
  ghi là đang diễn ra nhưng không có evidence.
- Chưa có SLO đo được ngoài các mục "gợi ý", chưa có capacity benchmark, backup/
  restore, schema rollout hoặc runbook tự động hóa.
