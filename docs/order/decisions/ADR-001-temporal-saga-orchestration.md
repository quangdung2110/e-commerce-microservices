---
doc_id: order-adr-001
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/order/architecture.md
  - docs/order/workflows.md
---

# ADR-001 — Dùng Temporal để điều phối Order Saga

## Trạng thái

`Accepted` trong thiết kế; chưa xác minh implementation. Ngày quyết định gốc không
được ghi lại.

> Current authority: ADR-014 loại late-payment workflow; ADR-015 bổ sung durable
> workflow-message dispatch/convergence. Danh sách workflow lịch sử bên dưới không còn
> là runtime catalog đầy đủ.

## Bối cảnh

Checkout tác động Order, Inventory, Promotion và Payment; refund chờ tín hiệu vật
lý có thể kéo dài; payment có thể tới đồng thời với timeout. Orchestration thuần
Kafka đòi hỏi tự xây state store, timeout job, retry, compensation, visibility và
crash recovery, dễ để saga lơ lửng.

## Quyết định

Dùng Temporal cho ba deterministic workflow:
`OrderCheckoutWorkflow`, `OrderRefundWorkflow` và
`OrderLatePaymentRecoveryWorkflow`. Mọi I/O nằm trong activity. Temporal giữ
timer/signal/history/retry; PostgreSQL FSM vẫn là safety boundary cho domain state.
WorkflowID ổn định theo checkout token hoặc order ID.

## Phương án đã cân nhắc

- Kafka choreography/orchestrator tự viết: giảm dependency nhưng visibility,
  timeout và recovery phức tạp; không chọn.
- Chỉ transaction DB/local job: không thể bao phủ remote side effect; không chọn.
- Temporal: thêm platform vận hành nhưng cho durable execution/replay; được chọn.

## Hệ quả

Code workflow đọc tuần tự, timer không giữ thread, crash có thể resume và
compensation được tổ chức LIFO. Đổi lại workflow code phải deterministic/replay-
compatible, activity phải idempotent và đội vận hành thêm Temporal cluster/UI/task
queue/history capacity.

## Ảnh hưởng migration

Cần ba task queue, worker registration, workflow/versioning strategy và mapping
event -> signal. Không được chuyển FSM logic hoàn toàn vào history hoặc update DB
trực tiếp từ consumer.

## Xác minh

Cần source workflow/activity, test replay/crash-resume/timer-signal race, cấu hình
namespace/task queue, metric backlog và E2E compensation tại từng failure point.
