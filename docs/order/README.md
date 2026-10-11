---
doc_id: order-overview
service: order
scope: target
status: implemented
owners:
  - Order Service team
evidence:
  - contracts/http/order/v1/openapi.yaml
  - contracts/http/order/internal/v1/openapi.yaml
  - contracts/events/order/v1/asyncapi.yaml
  - java/services/order-service/src/main/java/com/ecommerce/order
  - java/services/order-service/src/main/resources/db/migration/V1__order_baseline.sql
  - java/services/order-service/src/main/resources/db/migration/V2__admin_delivery_confirmation.sql
  - java/services/order-service/src/main/resources/kafka/debezium-connector-order-outbox.json
  - java/services/order-service/src/test/java/com/ecommerce/order
verified_against_commit: null
---

# Order Service — Tổng quan dịch vụ

> Đây là điểm bắt đầu để hiểu thiết kế của Order Service. Thiết kế đã được implement tại
> [`java/services/order-service`](../../java/services/order-service/README.md); các điểm còn mở
> trước khi code được chốt ở [ADR-020](./decisions/ADR-020-release-1-implementation-clarifications.md).
> Các contract có liên kết bên dưới là nguồn wire-level đồng bộ liên service.

## 1. Trạng thái dịch vụ

| Khía cạnh | Trạng thái |
|---|---|
| Thiết kế | `implemented`, scope `target`; tài liệu và contracts đồng bộ với code theo ADR-020 |
| Implementation | Java 21 + Spring Boot 3.3, JDBC + Flyway (`V1__order_baseline.sql`), Spring Kafka, Temporal Java SDK 1.25 tại `java/services/order-service`; bốn workflow, durable inbox/dispatch/message/checkpoint, multi-destination outbox, receipt observer/trimmer, Redis request mirror |
| Kiểm chứng | Unit test domain + integration test PostgreSQL (Testcontainers) + Temporal in-memory cho checkout, timeout/cancel/race, retry attempt, fulfilment (dispatch, Admin delivery confirmation), return/refund/reversal, no-payout, external remediation, Admin pre-dispatch, scope recovery và search; response HTTP validate với OpenAPI, outbox validate với JSON Schema; smoke test jar với Postgres + Temporal thật và Debezium connector với Kafka thật |
| Local runtime | `docker compose up -d order-service` (kèm `temporal`, `temporal-ui`); xem [development.md](./development.md) |
| Contract | Order HTTP v1.1.0 / AsyncAPI v1.1.0 `x-adoption-status: implemented`; chưa `verified` vì chưa có consumer contract test (xem [ADOPTION](../../contracts/ADOPTION.md)) |

## 2. Mục tiêu

Order Service quản lý vòng đời đơn hàng từ lúc Checkout submit đến khi thanh
toán, thực hiện, giao đủ, hủy hoặc hoàn tiền. Service tạo attempt thanh toán có
identity bất biến (và tối đa một transaction capture) ở cấp master order, tách việc
thực hiện đơn theo từng kho, giữ snapshot
sản phẩm và địa chỉ để lịch sử đơn không phụ thuộc dữ liệu upstream về sau.

Service cũng là orchestrator của các giao dịch phân tán với Inventory, Promotion
và Payment. Temporal quản lý timeout, retry, signal, compensation và khả năng
resume; PostgreSQL FSM là chốt bảo vệ cuối cùng cho tính hợp lệ của trạng thái.

## 3. Ranh giới trách nhiệm

Order Service chịu trách nhiệm:

- Sở hữu master order, delivery unit theo kho, dòng SKU snapshot, promotion đã áp dụng,
  payment-attempt mapping, refund/reconciliation case, durable inbox và checkpoints.
- Bảo vệ FSM của order và per-line fulfillment; physical dispatch không bị hiểu nhầm là delivery.
- Điều phối checkout saga, single-shot refund saga, capture/cancel race và
  financial reconciliation theo obligation; Payment dispute facts chỉ cập nhật
  obligation/evidence tương ứng.
- Đóng Inventory RefundScope bằng durable close/cancel Activity và recovery; Ops kết
  thúc return thiếu hàng bằng named no-payout decision, không tự đóng ở mốc 30 ngày.
- Phục vụ chi tiết đơn và tìm kiếm/lọc lịch sử đơn trực tiếp từ PostgreSQL.
- Phát integration event đáng tin cậy qua transactional outbox và CDC.
- Nhận fact Payment/Inventory vào durable inbox, commit workflow message rồi route bằng
  dispatcher; fact Payment tới trước khi attempt được bind được park/replay theo đúng owner transition.
- Sở hữu delivery state: Admin xác nhận giao hàng qua Order API là nguồn duy nhất đưa line sang
  `DELIVERED` (không có Delivery service, [ADR-021](./decisions/ADR-021-admin-delivery-confirmation.md)).

Order Service không chịu trách nhiệm:

- Tính lại giá/discount tại thời điểm submit; Checkout gửi snapshot đã tính phía
  server và xử lý lại khi Promotion reservation thất bại.
- Sở hữu tồn kho, ngân sách/ví voucher, payment transaction hoặc webhook Stripe.
- Restock vật lý; Inventory thực hiện sau khi hàng thực tế về kho.
- Xác thực credential/JWT tại domain; API Gateway cung cấp identity đáng tin cậy.
- Duy trì Elasticsearch read model, search projector, cold archive hoặc
  `product_sold_count` projection.

Mỗi local transaction chỉ bao phủ PostgreSQL của Order. Remote activity, Redis,
Kafka, Temporal và database service khác không cùng transaction với dữ liệu Order.

## 4. Quyền sở hữu dữ liệu

| Nhóm dữ liệu | Nguồn chân lý | Ghi chú quyền ghi |
|---|---|---|
| `orders` | Order Service | Chỉ Order workflow/application được chuyển FSM |
| `order_items` | Order Service | Delivery FSM theo từng đơn vị kho |
| `order_item_lines` | Order Service | Snapshot SKU/sản phẩm bất biến sau khi tạo |
| `order_promotions` | Order Service | Dấu vết allocation đã dùng; không phải ví/budget Promotion |
| `order_refund_cases` + `order_refund_case_lines` | Order Service | One lifetime refund control plane and selected-line scope |
| `order_admin_refund_attempts` | Order Service | Pre-dispatch release attempt that cannot burn the lifetime refund entitlement on rejection |
| `order_payment_attempts` / reconciliation envelope + obligations/evidence/decisions | Order Service | Payment attempt↔transaction proof and authenticated manual reconciliation decisions |
| `order_operations` / workflow dispatches/messages / consumed events / saga checkpoints | Order Service | Durable command result, Temporal start/signal, event inbox, and remote-command recovery proof |
| `order_notification_commands` + `outbox_events` + delivery receipts | Order Service | Durable notification identity/sequence, multi-destination append-only relay và cleanup proof |
| Redis request replay mirrors | Fast-path tạm thời cho bốn command | PostgreSQL inbox/dispatch/checkpoint + FSM giữ bất biến bền vững |
| Payment, inventory, promotion state | Service tương ứng | Order chỉ giữ ID/snapshot/evidence cần điều phối |

Service khác không được ghi trực tiếp vào PostgreSQL của Order. Tín hiệu ngoài đi
qua API, event hoặc Temporal signal được Order xử lý.

## 5. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ |
|---|---|
| Tạo order từ checkout | Tạo master/sub-order và snapshot, reserve tài nguyên, khởi tạo thanh toán |
| Xem chi tiết order | Trả lịch sử denormalized có kiểm tra ownership |
| Tìm kiếm/lọc order | Trả order-level card theo buyer/admin, tab, trạng thái, thời gian, tiền và tên sản phẩm |
| Retry thanh toán | Request numbered Payment attempt mới chỉ sau `FAILED`, trước immutable deadline và khi resource holds còn hiệu lực |
| Hủy order | Resolve Payment cancel/capture fact before pre-payment compensation; admin paid action goes refund lane |
| Yêu cầu trả hàng | Chấp nhận đúng một Buyer request hợp lệ và khóa selected full-quantity line scope |
| Xử lý thanh toán | Validate mapped Payment attempt fact, freeze captured transaction, then forward-repair post-payment checkpoints |
| Physical dispatch/delivery | Map Inventory fact only to `DISPATCHED`; Admin delivery confirmation moves dispatched lines to `DELIVERED` and the last one completes the master |
| Hoàn tiền | Buyer chờ Inventory final-inspect selected scope; admin chỉ automatic sau Inventory release toàn bộ committed lines trước dispatch; Payment gọi đúng một lần kể cả inspection FAIL |
| Đóng return scope | Buyer terminal refund result tạo frozen close intent; approved no-payout Ops decision dùng incomplete close/zero-receipt cancel. Financial outcome và finalization progress được theo dõi riêng |
| Capture/cancel anomaly | Capture thắng khi còn `CANCELLING` đi forward repair; success sau terminal non-capture mở reconciliation obligation, không hồi sinh order |
| Phát message vòng đời | Công bố Order facts và Notification commands theo multi-destination outbox CDC at-least-once |

## 6. Phụ thuộc

| Hướng | Service/Hệ thống | Giao thức | Mục đích | Bắt buộc/Tùy chọn | Contract |
|---|---|---|---|---|---|
| Inbound | Checkout | HTTP | Tạo order từ checkout snapshot | Bắt buộc | [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml) |
| Inbound | Buyer/Admin qua Gateway | HTTP | Đọc, tìm kiếm, hủy, retry payment và return/refund | Bắt buộc | [Order public OpenAPI v1](../../contracts/http/order/v1/openapi.yaml) |
| Inbound | Payment | Kafka | Payment/refund result | Bắt buộc | [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml) |
| Inbound | Inventory | Kafka | Physical line-dispatch và selected-scope inspection completion | Bắt buộc | [Inventory events v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| Inbound | Admin qua Gateway | HTTP | Xác nhận giao hàng từng line đã dispatch (`delivery-confirmations`) | Bắt buộc để hoàn tất đơn/mở Buyer return | [Order public OpenAPI v1](../../contracts/http/order/v1/openapi.yaml) |
| Outbound | Inventory | HTTP activity | Reserve exact plan/release/commit, selected scope, atomic pre-dispatch release | Bắt buộc | [Inventory workflow v1](../../contracts/http/inventory/workflow/v1/openapi.yaml) |
| Outbound | Promotion | HTTP activity | Reserve/cancel runtime and durable confirm; Order has no technical-cancellation capability | Bắt buộc khi có promotion | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) — implemented |
| Outbound | Payment | HTTP activity | Tạo PaymentIntent attempt, cancel và refund captured transaction | Bắt buộc | [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml) |
| Outbound | Kafka | CDC | Hai Order facts cho Cart/Review và năm Notification commands | Bắt buộc | Order/Notification AsyncAPI v1 |
| Internal | Temporal | Workflow/Activity/Signal | Durable saga orchestration | Bắt buộc | Workflow identity trong tài liệu |
| Internal | PostgreSQL | SQL | Source of truth và read path | Bắt buộc | Flyway `V1__order_baseline.sql`, `V2__admin_delivery_confirmation.sql` |
| Internal | Redis | Redis commands | Replay request nhanh cho cancel/admin-refund/return/payment-attempt | Tùy chọn; lỗi thì fallback PostgreSQL | Key/TTL trong `data.md` |
| Internal | Debezium/Kafka Connect | WAL CDC | Relay outbox sang Kafka | Bắt buộc | `src/main/resources/kafka/debezium-connector-order-outbox.json` (smoke test thủ công) |

Inventory reservation commands return durable HTTP receipts; Order does not consume
reservation-result acknowledgment events. Unknown Reserve/Commit/Release
outcomes use the original command query/replay and durable Saga checkpoints. The
Inventory Kafka contract contains only dispatch and selected-scope inspection completion
([Inventory ADR-020](../inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md)).

## 7. Bất biến quan trọng

- [`INV-ORD-001`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): một
  `checkout_token` chỉ tạo một checkout workflow/order logic.
- [`INV-ORD-002`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): chỉ workflow
  được commit các mốc tài chính/refund; Kafka consumer không ghi FSM trực tiếp.
- [`INV-ORD-003`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Buyer chỉ được
  submit return một lần trên mỗi order; guard không reset sau refund fail/reverse.
- [`INV-ORD-004`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Order chỉ có một
  refund case/refund transaction trọn đời trên mỗi order.
- [`INV-ORD-005`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Buyer chỉ gọi
  Payment sau scope completion; Admin chỉ sau Inventory atomic all-line
  pre-dispatch release evidence.
- [`INV-ORD-006`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): chỉ phát
  `com.ecommerce.order.completed.v1` khi tất cả line đã `DELIVERED` bằng Admin delivery confirmation và CAS master thành công.
- [`INV-ORD-007`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): chỉ `PAID` và
  `COMPLETED` có registered integration event; state, sequence và outbox tương ứng
  commit trong cùng transaction.
- [`INV-ORD-008`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): customer refund
  không mutate Promotion; voucher và budget đã confirm được giữ nguyên.
- [`INV-ORD-018`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Payment/Inventory
  facts enter a durable inbox only after semantic identity/version validation.
- [`INV-ORD-019`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): post-capture work
  forward-repairs or reconciles; it never rolls business state backward.
- [`INV-ORD-021`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): allocation plan,
  `Checkout line_reference→order_line_id` bridge và line→warehouse mapping được persist
  trước Reserve/dispatch.
- [`INV-ORD-022`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): only the immutable
  captured payment transaction is eligible for refund.
- [`INV-ORD-025/026`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Buyer scope closure
  needs durable command evidence/recovery; incomplete no-payout closure needs an audited
  decision and never resets lifetime case/scope guards.
- [`INV-ORD-027/028/029/030`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): DB-to-Temporal
  message durability, FAILED-only retry under immutable deadline, stream-scoped event
  dedup và convergence sau proof out-of-order là bắt buộc.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Kiến trúc](./architecture.md) | Thành phần, Temporal/Kafka/CDC và consistency | Khi thay đổi cấu trúc hoặc dependency |
| [Miền nghiệp vụ](./domain.md) | Aggregate, luật, FSM và lỗi miền | Khi thay đổi business logic |
| [Giao diện](./interfaces.md) | HTTP, remote activity và event semantics | Khi tích hợp service |
| [Luồng nghiệp vụ](./workflows.md) | Happy path, timeout, retry, race và recovery | Khi sửa use case/saga |
| [Dữ liệu](./data.md) | Logical schema, index, transaction, Redis và lifecycle | Khi sửa persistence/search |
| [Vận hành](./operations.md) | Worker, failure mode, alert và runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Build/test/migration/chạy local của `java/services/order-service` | Khi sửa code hoặc chạy local |
| [Quyết định](./decisions/README.md) | ADR bảo toàn lý do của thiết kế cũ | Khi đánh giá trade-off |

## 9. Khoảng trống đã biết

- Không có Delivery service: `DELIVERED`/`COMPLETED` đi qua Admin delivery confirmation (ADR-021), nên
  không có bằng chứng giao hàng xác thực từ bên thứ ba, và đơn đã dispatch mà Admin quên xác nhận sẽ ở
  `PROCESSING` (gauge `order.fulfillment.delivery_confirmation_overdue`, mặc định 7 ngày). Checkout
  Service đã có runtime (`java/services/checkout-service`) nhưng chưa có E2E tự động qua Order
  thật: test của Checkout dùng fixture đã validate với OpenAPI internal của Order, còn test của
  Order dùng contract fake.
- Contracts mới ở mức `implemented`: chưa có consumer contract test với provider thật (Checkout,
  frontend, Finance/Ops, Cart/Review/Notification); Debezium mới smoke test thủ công.
- Chưa có Temporal replay test trên history production, Kubernetes manifest, phân pod
  HTTP/consumer/worker hay mesh `AuthorizationPolicy` thật.
- Analytics chưa có runtime consumer, nên không được coi là consumer của Order event cho
  đến khi interaction/code/contract được đăng ký. Notification **không** consume Order fact;
  Order gửi request `com.ecommerce.notification.requested.v1` cho 5 template `ORDER_*`
  (interaction `order-requests-notifications`, xem `interfaces.md` §5.1).
- Chưa có SLO, capacity benchmark, retention đầy đủ cho order data, backup/restore,
  schema rollout hoặc runbook tự động hóa.
- Các chính sách wire/business không còn để mở; trước production vẫn cần consumer
  contract tests, Temporal replay tests và operational evidence (dashboard, alert, load).
