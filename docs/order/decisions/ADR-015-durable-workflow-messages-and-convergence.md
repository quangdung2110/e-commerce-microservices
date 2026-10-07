---
doc_id: order-adr-015
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - docs/order/architecture.md
  - docs/order/data.md
  - docs/order/workflows.md
---

# ADR-015 — Durable workflow messages và convergence

## Trạng thái

`Accepted` — 2026-10-05. Refine ADR-001, ADR-005 và ADR-011.

## Bối cảnh

Transaction inbox rồi gọi Temporal trực tiếp có dual-write gap: process có thể chết sau
DB commit trước signal, hoặc signal thành công nhưng trước ACK. Uniqueness theo order/revision
cũng đụng giữa nhiều provider stream. Delivery proof có thể đến trước dispatch, còn
financial repair có thể hoàn tất sau mọi delivery proof.

## Quyết định

- Consumer/API atomically ghi domain evidence, operation khi có, và
  `order_workflow_messages`; chỉ ACK Kafka/trả accepted sau commit.
- Inbound `message_id` được scope bởi source + event + target workflow + message type;
  không dùng raw event UUID làm global identity. API dùng stable operation ID.
- Dispatcher claim row bằng lease, gọi `SignalWithStart` với stable `message_id`, và chỉ
  mark delivered sau Temporal durable acknowledgement. Workflow dedupe message ID trước
  activity; per-stream high-watermark + bounded recent IDs được carry qua
  `Continue-As-New`, vì run history cũ không phải dedup store của run mới.
- Inbox PK là `(source,event_id)`; semantic uniqueness là
  `(source,event_type,stream_id,source_revision)`.
- Proof thiếu prerequisite được park `PENDING_PREREQUISITE`, không drop. Mọi arrival của
  prerequisite hoặc reconciler đều replay proof.
- Closed target không cho phép Workflow ID reuse. Dispatcher chỉ terminal-no-op khi DB
  chứng minh applied/superseded, hoặc transactionally reroute open obligation sang named
  recovery/convergence workflow; ambiguous target giữ retry/quarantine.
- `TryCompleteOrder` là convergence routine dùng sau cả delivery và financial progress;
  chỉ complete khi mọi line delivered và mọi required obligation đóng.
- Workflow Execution Timeout không phải correctness boundary; workflow dùng
  continue-as-new hoặc durable reconciler cho work dài hạn.

## Hệ quả và xác minh

Cần message dispatcher, pending-proof/convergence reconciler và metrics oldest age.
Failure injection bắt buộc tại cả hai phía Temporal RPC, duplicate signal trước/sau
Continue-As-New, stream revision
collision, closed-target applied/no-op/reroute/ambiguous branches, proof-before-dispatch
và delivery-before-financial-repair.
