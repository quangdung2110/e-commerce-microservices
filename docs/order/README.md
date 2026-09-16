---
doc_id: order-overview
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence: []
verified_against_commit: null
---

# Order Service — Tổng quan dịch vụ

> Đây là điểm bắt đầu để hiểu thiết kế mục tiêu của Order Service. Bộ tài liệu
> được tái cấu trúc từ các tài liệu thiết kế Order cũ; chưa được xác minh với một
> implementation hiện hữu và chưa nhằm thống nhất contract giữa các service.

## 1. Trạng thái dịch vụ

Order Service đang ở mức thiết kế `proposed`, phạm vi `target`. Chưa tìm thấy mã
nguồn Order Service, migration hay cấu hình triển khai trong repository để chứng
minh runtime, framework, port hoặc hành vi production. Thiết kế chọn PostgreSQL,
Redis, Kafka, Debezium/Kafka Connect và Temporal, nhưng các lựa chọn này chỉ được
xem là đã triển khai sau khi có evidence tương ứng.

Mức áp dụng contract liên service chưa được đánh giá trong lần tái cấu trúc này.
Contract hiện có, nếu có, không được dùng để âm thầm thay đổi đặc điểm của thiết
kế cũ.

## 2. Mục tiêu

Order Service quản lý vòng đời đơn hàng từ lúc Checkout submit đến khi thanh
toán, thực hiện, giao đủ, hủy hoặc hoàn tiền. Service tạo một giao dịch thanh toán
duy nhất ở cấp master order, tách việc thực hiện đơn theo từng kho, giữ snapshot
sản phẩm và địa chỉ để lịch sử đơn không phụ thuộc dữ liệu upstream về sau.

Service cũng là orchestrator của các giao dịch phân tán với Inventory, Promotion
và Payment. Temporal quản lý timeout, retry, signal, compensation và khả năng
resume; PostgreSQL FSM là chốt bảo vệ cuối cùng cho tính hợp lệ của trạng thái.

## 3. Ranh giới trách nhiệm

Order Service chịu trách nhiệm:

- Sở hữu master order, delivery unit theo kho, dòng SKU snapshot, promotion đã áp
  dụng, refund case/attempt, durable event inbox và workflow-command checkpoints.
- Bảo vệ FSM của order và order item; physical dispatch không bị hiểu nhầm là delivery.
- Điều phối checkout saga, refund saga, late-payment recovery và financial reconciliation.
- Phục vụ chi tiết đơn và tìm kiếm/lọc lịch sử đơn trực tiếp từ PostgreSQL.
- Phát integration event đáng tin cậy qua transactional outbox và CDC.
- Nhận tín hiệu Payment/Inventory rồi route vào workflow hoặc cập nhật delivery
  FSM theo đúng owner transition.

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
| `order_workflow_dispatches` / `order_consumed_events` / `order_saga_checkpoints` | Order Service | Durable Temporal start, event inbox, and remote-command recovery proof |
| `outbox_events` | Order Service | Log append-only để CDC phát event |
| Redis idempotency/lock keys | Fast-path tạm thời | PostgreSQL inbox/dispatch/checkpoint + FSM giữ bất biến bền vững |
| Payment, inventory, promotion state | Service tương ứng | Order chỉ giữ ID/snapshot/evidence cần điều phối |

Service khác không được ghi trực tiếp vào PostgreSQL của Order. Tín hiệu ngoài đi
qua API, event hoặc Temporal signal được Order xử lý.

## 5. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ |
|---|---|
| Tạo order từ checkout | Tạo master/sub-order và snapshot, reserve tài nguyên, khởi tạo thanh toán |
| Xem chi tiết order | Trả lịch sử denormalized có kiểm tra ownership |
| Tìm kiếm/lọc order | Trả order-level card theo buyer/admin, tab, trạng thái, thời gian, tiền và tên sản phẩm |
| Hủy order | Resolve Payment cancel/capture fact before pre-payment compensation; admin paid action goes refund lane |
| Yêu cầu trả hàng | Chấp nhận đúng một Buyer request hợp lệ và khóa selected full-quantity line scope |
| Xử lý thanh toán | Validate original Payment fact, commit financial watermark, then forward-repair post-payment checkpoints |
| Physical dispatch/delivery | Map Inventory fact only to `DISPATCHED`; customer delivery/complete requires a separate proof contract |
| Hoàn tiền | Buyer chờ Inventory final-inspect selected scope; admin chỉ automatic sau Inventory release toàn bộ committed lines trước dispatch; Payment gọi đúng một lần kể cả inspection FAIL |
| Khôi phục payment muộn | Fenced recovery of the same timeout-released Inventory root; nếu không an toàn thì financial reconciliation, không system auto-refund |
| Phát event vòng đời | Công bố event Order/OrderItem theo outbox CDC at-least-once |

## 6. Phụ thuộc

| Hướng | Service/Hệ thống | Giao thức | Mục đích | Bắt buộc/Tùy chọn | Contract |
|---|---|---|---|---|---|
| Inbound | Checkout | HTTP | Tạo order từ checkout snapshot | Bắt buộc | Chưa chuẩn hóa trong phạm vi này |
| Inbound | Buyer/Admin qua Gateway | HTTP | Đọc, tìm kiếm, hủy và return | Bắt buộc | Chưa có OpenAPI Order đầy đủ |
| Inbound | Payment | Kafka | Payment/refund result | Bắt buộc | Tài liệu thiết kế hiện hành |
| Inbound | Inventory | Kafka | Physical line-dispatch và selected-scope inspection completion | Bắt buộc | [Inventory events v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| Outbound | Inventory | HTTP/gRPC activity | Reserve/release/commit, fenced timeout recovery, selected scope, atomic pre-dispatch release | Bắt buộc | [Inventory workflow v1](../../contracts/http/inventory/workflow/v1/openapi.yaml) |
| Outbound | Promotion | HTTP activity | Reserve/cancel runtime and durable confirm; technical cancellation is legacy-only; no call when customer refund | Bắt buộc khi có promotion | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) — proposed |
| Outbound | Payment | HTTP/gRPC activity | Tạo PaymentIntent, cancel và refund | Bắt buộc | Chưa chuẩn hóa trong phạm vi này |
| Outbound | Kafka consumers | Kafka | Event vòng đời order/item | Bắt buộc | Có một phần Order completed v1; không đối soát lần này |
| Internal | Temporal | Workflow/Activity/Signal | Durable saga orchestration | Bắt buộc | Workflow identity trong tài liệu |
| Internal | PostgreSQL | SQL | Source of truth và read path | Bắt buộc | Logical design, chưa có migration evidence |
| Internal | Redis | Redis commands | Dedup request/event và FSM lock nhanh | Bắt buộc theo thiết kế hiện tại | Key/TTL trong `data.md` |
| Internal | Debezium/Kafka Connect | WAL CDC | Relay outbox sang Kafka | Bắt buộc | Cấu hình chưa có evidence |

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
  `ORDER_COMPLETED` khi tất cả order item đã `DELIVERED` và CAS master thành công.
- [`INV-ORD-007`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): state change và
  outbox event tương ứng commit trong cùng transaction.
- [`INV-ORD-008`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): customer refund
  không mutate Promotion; voucher và budget đã confirm được giữ nguyên.
- [`INV-ORD-018`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): Payment/Inventory
  facts enter a durable inbox only after semantic identity/version validation.
- [`INV-ORD-019`](./domain.md#4-quy-tắc-và-bất-biến-nghiệp-vụ): post-capture work
  forward-repairs or reconciles; it never rolls business state backward.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Kiến trúc](./architecture.md) | Thành phần, Temporal/Kafka/CDC và consistency | Khi thay đổi cấu trúc hoặc dependency |
| [Miền nghiệp vụ](./domain.md) | Aggregate, luật, FSM và lỗi miền | Khi thay đổi business logic |
| [Giao diện](./interfaces.md) | HTTP, remote activity và event semantics | Khi tích hợp service |
| [Luồng nghiệp vụ](./workflows.md) | Happy path, timeout, retry, race và recovery | Khi sửa use case/saga |
| [Dữ liệu](./data.md) | Logical schema, index, transaction, Redis và lifecycle | Khi sửa persistence/search |
| [Vận hành](./operations.md) | Worker, failure mode, alert và runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Checklist build/test/migration khi có code | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | ADR bảo toàn lý do của thiết kế cũ | Khi đánh giá trade-off |

## 9. Khoảng trống đã biết

- Chưa có implementation, migration Order, OpenAPI đầy đủ, cấu hình Temporal,
  Kafka Connect, Redis hoặc deployment để xác minh.
- Tài liệu cũ query các cột `paid_at`, `completed_at`, `cancelled_at`, `refunded_at`
  nhưng phần định nghĩa bảng `orders` chưa khai báo các cột này.
- Mô tả cũ vừa nói metadata `refund_waiting_inventory_confirmation` được phát
  trong `ORDER_STATUS_CHANGED`, vừa nói Phase 20 đã loại metadata refund khỏi event.
- Mô tả `ORDER_REFUND_FAILED` có chỗ yêu cầu chỉ `error_code`, có ví dụ vẫn chứa
  `error_message`; cần chốt ở lần refactor contract sau.
- Cách Inventory Ship consumer lấy `inventory_reservations` chưa nêu rõ qua API,
  event projection hay local data; không được hiểu là cho phép query database
  Inventory trực tiếp.
- Chưa có SLO, capacity benchmark, retention đầy đủ cho order data, backup/restore,
  schema rollout hoặc runbook tự động hóa.
