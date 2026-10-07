---
doc_id: <service-id>-operations
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-runtime-config>
  - <path-to-observability-config>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Operations

> File này trả lời: “Service hỏng thì phát hiện và xử lý thế nào?”

## 1. SLO và SLI

Mô tả mục tiêu và cách đo:

- Availability.
- Latency.
- Error rate.
- Throughput.
- Consumer lag hoặc workflow completion time.
- Error budget nếu có.

Không dùng từ như “high performance” hoặc “highly available” nếu chưa có metric
và mục tiêu đo được.

## 2. Health endpoints

Mô tả:

- Liveness.
- Readiness.
- Startup probe nếu có.
- Dependency nào ảnh hưởng readiness.
- Khi nào instance phải bị loại khỏi traffic.

## 3. Configuration

| Variable/Property | Purpose | Required | Default | Secret | Restart required |
|---|---|---|---|---|---|
| `<name>` | `<purpose>` | Yes/No | `<value>` | Yes/No | Yes/No |

Không ghi giá trị secret thật vào tài liệu.

## 4. Observability

Liệt kê:

- Metrics và label có cardinality an toàn.
- Structured log events.
- Trace/span quan trọng.
- Correlation, request, event và workflow identifiers.
- Dashboard liên quan.

## 5. Alerts

| Alert | Condition | Severity | Owner | Runbook |
|---|---|---|---|---|
| `<name>` | `<measurable condition>` | `<severity>` | `<owner>` | `<link>` |

## 6. Failure modes

Mô tả hành vi khi:

- Database unavailable hoặc exhausted connection pool.
- Redis unavailable/corrupted.
- Kafka unavailable hoặc consumer lag tăng.
- Downstream timeout/error.
- External provider lỗi.
- Partial hoặc mixed-version deployment.

## 7. Degraded behavior

Nêu service tiếp tục cung cấp được chức năng nào, chức năng nào bị từ chối, dữ
liệu nào có thể stale và fallback nào được phép sử dụng.

## 8. Scaling và capacity

Mô tả:

- Scale dựa trên CPU, memory, RPS, queue depth hay consumer lag.
- Bottleneck đã biết.
- Worker nào không thể scale tùy ý.
- Partition, connection pool và rate-limit constraint.
- Kết quả load test hoặc capacity assumption nếu có.

## 9. Deployment và rollback

Mô tả:

- Startup/deployment order.
- Migration order.
- Backward-compatible rollout.
- Rollback procedure.
- Zero-downtime constraints.
- Feature flag hoặc canary strategy.

## 10. Runbooks

Cung cấp hoặc liên kết runbook cho ít nhất:

- Consumer lag tăng cao.
- DLQ có message.
- Cache corruption.
- Database connection exhaustion.
- Reconciliation failure.
- Dependency outage.

Mỗi runbook cần có triệu chứng, bước chẩn đoán an toàn, cách khắc phục, cách xác
nhận phục hồi và escalation owner.

## 11. Workers và scheduled jobs

Với mỗi worker/job, mô tả:

- Mục đích và owner.
- Trigger hoặc schedule.
- Input/output.
- Concurrency và locking.
- Retry và timeout.
- Idempotency.
- Cách chạy lại an toàn.
- Metric/alert.
- Cleanup và retention liên quan.
