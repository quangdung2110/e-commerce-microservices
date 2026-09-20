---
title: Checkout Operations
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../mesh/runtime-contract.md
  - ../gateway/runtime-contract.md
tags: [checkout, operations]
---

# Checkout Operations

## Health

- `/health/live`: chỉ process health.
- `/health/ready`: Redis và cấu hình bắt buộc sẵn sàng; không fan-out tới mọi downstream trên từng probe.
- Dependency health được phản ánh bằng metrics/circuit state, không làm readiness flapping vì một lỗi ngắn hạn.

## Cấu hình bắt buộc

| Nhóm | Cấu hình |
|---|---|
| Redis | endpoint, auth/TLS, operation timeout, pool, HMAC lookup-key version và payload envelope-encryption key version |
| Downstream | Cart/Product/User/Inventory/Promotion/Order endpoints và mTLS identities |
| Time | quote validity 20m, pre-submit record retention 30m, idempotency 5m, submit owner timeout target 30s, recovery 30m, success result 24h |
| Safety | max cart lines/quantity, request/body limits, circuit breaker thresholds |
| Observability | OTLP endpoint, sample rate, log level, environment/service version |

Secret chỉ lấy từ secret manager/environment injection, không commit trong config và không log.

## Deadline và retry

- Gateway timeout phải lớn hơn Checkout end-to-end budget; giá trị production được chốt bằng load test.
- Mỗi dependency call có deadline riêng và cancellation propagation.
- Chỉ retry lỗi transport/5xx có jitter trong ngân sách.
- Order retry luôn dùng cùng immutable payload và idempotency key.
- Không retry business conflict, validation error hoặc insufficient stock.

## Metrics

- HTTP RED metrics theo route/status/code.
- Preview latency theo wave/dependency; cart size histogram.
- Submit transition totals, CAS contention và time in `SUBMITTING`/`RETRYABLE`.
- Order recovery lookup outcome và `submission_result_unknown_total`.
- Redis latency/error/eviction và TTL-expired sessions.
- Downstream call latency/error/circuit state.

Không dùng token, user ID, cart ID, SKU hoặc promotion ID làm metric label.

## Logging/tracing

Log JSON gồm `timestamp`, `level`, `service`, `environment`, `trace_id`, `span_id`, `request_id`, route template, status, error code và duration. Application và Gateway phải redact/hash segment `{checkout_token}`; không ghi raw request path cho status route. Token chỉ log fingerprint ngắn không đảo ngược; không log payload Order đầy đủ, address PII, auth headers hoặc secrets.

Span chính: `checkout.preview`, `checkout.submit`, `checkout.recovery`; child spans theo dependency và Redis operation.

## Alert gợi ý

- Checkout 5xx/dependency error rate tăng.
- p95/p99 preview hoặc submit vượt budget.
- Redis error/eviction hoặc memory pressure.
- Session kẹt `SUBMITTING` quá recovery threshold.
- `SUBMISSION_RESULT_UNKNOWN` tăng.
- Order idempotency conflict hoặc duplicate-detection tăng.

Ngưỡng số cụ thể phải được chốt bằng SLO/traffic thực tế; không ghi số giả như cam kết production.

## Runbooks

### Inventory hoặc Product lỗi

Preview fail-closed; không bật degraded stock/pricing. Xác định dependency, giảm retry storm/circuit breaker và theo dõi phục hồi.

### Order timeout

Không yêu cầu client tạo checkout mới ngay. Dùng status endpoint; Checkout query Order bằng idempotency key và hoàn tất local result.

### Redis mất dữ liệu

Ngăn submit mới nếu không thể đảm bảo idempotency materialization. Với submit đã bắt đầu, ưu tiên Order lookup; đưa các trường hợp không thể phân giải vào recovery queue/manual investigation.

### Rollout

1. Deploy Redis schema/script compatibility.
2. Deploy Checkout upstream tối thiểu hai replica.
3. Chạy smoke/contract tests internal.
4. Bật Gateway route canary.
5. Theo dõi errors/recovery rồi tăng traffic.

Rollback route trước, nhưng giữ service/recovery đủ lâu để các token đã phát hành còn truy vấn được.
