---
doc_id: checkout-operations
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Vận hành

> Thiết kế cũ chốt timeout, fallback, TTL và degraded contract nhưng chưa chốt
> SLO số, config runtime, alert hay runbook production. Các mục “cần” dưới đây là
> acceptance criteria, không phải cam kết đã triển khai.

## 1. SLO và SLI

Mục tiêu latency đã nêu trong thiết kế cũ (chưa có SLO chính thức):

| Chỉ số | Mục tiêu thiết kế |
|---|---|
| Preview toàn phần (sourcing đầy đủ) | < 150ms |
| Preview đổi voucher (chỉ gọi Promotion) | < 20ms |
| Preview đổi địa chỉ (gọi lại Inventory) | < 120ms |
| Tổng Preview API hard limit | 5s (fallback từng phần) |
| Price Staleness Check overhead ở Submit | < 8ms (lookup ~2–4ms + hash ~0.2ms) |

Cần định nghĩa SLI/SLO cho availability và error rate của Preview/Submit; tỷ lệ
`PRICE_CHANGED`, `PROMOTION_RECALCULATION_NEEDED`, `503`, `410`; tỷ lệ response
degraded; tỷ lệ recovery thành công từ payload cache. Error budget chưa chốt.

## 2. Health endpoints

Chưa thiết kế. Khi có implementation: liveness chỉ kiểm tra process; readiness
phải phụ thuộc Redis (session/lock là bắt buộc cho cả Preview lẫn Submit) — Redis
unavailable thì instance không phục vụ được và phải bị loại khỏi traffic.
Downstream (Cart/User/Product/Inventory/Promotion/Order) không nên đưa vào
readiness vì đã có timeout/fallback theo từng scope.

## 3. Cấu hình cần công bố

| Nhóm property | Mục đích | Giá trị thiết kế hiện biết | Secret |
|---|---|---|---|
| Redis endpoint/auth | Session, lock, cache | Chưa chốt | Có |
| Session TTL | `checkout:session` | 20 phút | Không |
| Payload/response cache TTL | Recovery/idempotency | 20 phút | Không |
| Routing cache TTL | `checkout:routing` | 20 phút | Không |
| Preview lock TTL | `checkout:lock:preview` | 5 giây | Không |
| Submit lock TTL | `checkout:lock:submit` | 30 giây | Không |
| Preview rate limit | `checkout:ratelimit:preview` | 10 req/phút | Không |
| Submit rate limit | `checkout:ratelimit:submit` | 3 req/phút | Không |
| Timeout User Service | Wave 1 | 500ms | Không |
| Timeout Cart Service | Wave 1 | 50ms | Không |
| Timeout Inventory routing | Wave 1 | 3s | Không |
| Timeout Promotion calculation | Wave 2 | 2s | Không |
| Timeout tổng Preview | API | 5s | Không |
| Order call retry | Submit | Tối đa 3 lần | Không |
| Product gRPC endpoint/deadline | Wave 1, staleness check | Deadline chưa chốt | Không |
| mTLS/service credential cho User, Promotion, Inventory | Internal calls | Theo contract từng service | Có |
| Gateway profile `promotion-discovery` | Discovery rate limit | 20 req/phút/user (thuộc Gateway) | Không |

Tên environment variable, default, restart requirement và secret manager chưa
được xác minh.

## 4. Quan sát hệ thống

Metrics tối thiểu:

- Latency và error rate của Preview/Submit theo status code; latency theo từng
  downstream và theo wave.
- Tỷ lệ `429` (rate limit/lock preview), `423` (submit lock), `409` theo từng
  error code, `404`, `410`, `503`, `422`.
- Routing cache hit/miss; tỷ lệ response degraded (`DEGRADED_PROMOTION`,
  `DEGRADED_STOCK`).
- Số lần recovery từ payload cache và kết quả; số lần Lua trả `-1`/`-2`.
- Redis command error, lock contention; không label bằng `user_id` hay token.

Structured log/trace: `correlation_id`, `user_id`, `checkout_token`,
`items_hash`, `order_id` khi có; `promotion_budget_version` được ghi để quan sát
drift/read-time diagnostics. Không log số tiền client gửi như dữ liệu tin cậy.
Dashboard chưa có.

## 5. Alerts

| Alert | Điều kiện | Severity | Owner | Runbook |
|---|---|---|---|---|
| Redis unavailable | Lỗi kết nối/`SETNX`/Lua vượt ngưỡng | Critical | Checkout team | §10.1 |
| Tỷ lệ `503` Submit tăng | Order timeout ×3 vượt ngưỡng | High | Checkout + Order | §10.2 |
| Tỷ lệ `410 Gone` tăng | Payload hết TTL trước khi recovery thành công | High | Checkout + Order | §10.2 |
| Tỷ lệ degraded tăng | Promotion/Inventory timeout vượt ngưỡng | High | Checkout + downstream | §10.3 |
| Tỷ lệ `PRICE_CHANGED` bất thường | Hash lệch tăng đột biến | Medium | Checkout + Product/Promotion | §10.4 |
| Tỷ lệ `PROMOTION_RECALCULATION_NEEDED` tăng | Reserve fail vượt ngưỡng | Medium | Checkout + Promotion | §10.5 |

Ngưỡng số chưa chốt.

## 6. Failure modes

| Tình huống | Hành vi |
|---|---|
| Redis unavailable | Không đọc/ghi được session, lock, rate limit → Preview và Submit đều thất bại (lỗi hạ tầng). Không có DB fallback vì Checkout stateless |
| Redis mất key session sớm | Submit → `404`; user Preview lại |
| Redis mất key payload sớm | Client retry → `410` sớm hơn 20 phút |
| Redis Lua fail | Distributed lock là fallback layer chặn duplicate submit |
| Cart timeout (50ms) | Lỗi Preview |
| User timeout (500ms) | Dùng địa chỉ cũ từ session nếu có |
| Product gRPC lỗi ở Preview | Không validate được status/giá → Preview lỗi |
| Product gRPC lỗi ở Submit | Không tính được hash → không chạy Lua, nhả lock; session vẫn `PREVIEW_READY` |
| Inventory timeout (3s) | `DEGRADED_STOCK`; Submit cần ack |
| Promotion timeout (2s) | `DEGRADED_PROMOTION`, không áp voucher; Submit cần ack |
| Promotion Discovery budget guard lỗi | Discovery fail-open (degraded); Reserve ở Submit vẫn là quyết định cuối |
| Order timeout/down | Retry 3 lần → `503`; token `SUBMITTED`, payload giữ; recovery khi client retry |
| Order trả reserve fail | `409 PROMOTION_RECALCULATION_NEEDED`; token `SUBMITTED` |
| Promotion lỗi khi re-evaluate sau reserve fail | Không được trừ cục bộ; trả lỗi, client retry cùng token đi nhánh recovery |
| Mixed-version deployment | Chưa đánh giá; thay đổi format session/hash giữa version cần cân nhắc vì session sống 20 phút |

## 7. Degraded behavior

- Promotion timeout: Preview vẫn trả báo giá không áp voucher với cờ
  `DEGRADED_PROMOTION`; Submit được phép khi user ack.
- Inventory timeout: Preview trả giá tạm thời với cờ `DEGRADED_STOCK`; Submit
  được phép khi user ack (tài liệu cũ còn nhắc “Flat-rate config fallback”).
- Voucher tray degraded (budget guard fail-open): mã có thể hiển thị dù đã cạn
  budget; Reserve ở Submit sẽ bắt và đi nhánh recalculation.
- Redis unavailable: không có chức năng nào hoạt động.
- Order unavailable: Preview vẫn hoạt động; Submit trả `503` và client retry.

## 8. Scaling và capacity

- API server stateless, scale ngang theo RPS/CPU; không có worker hay singleton.
- Bottleneck đã biết: Redis (mọi request chạm session/lock/rate limit); fan-out
  Wave 1 tới N kho Inventory; Product gRPC batch ở cả Preview lẫn Submit.
- Preview lock 5 giây và rate limit 10/phút giới hạn tải mỗi user gửi xuống
  downstream; Submit rate limit 3/phút.
- Routing cache 20 phút giảm gọi Inventory khi đổi voucher.
- Load test và capacity assumption chưa có.

## 9. Deployment và rollback

- Không có migration. Deploy/rollback chỉ ảnh hưởng process stateless; session
  đang sống 20 phút phải đọc được bởi cả version cũ và mới (format Hash field,
  cách tính `pricing_hash`).
- Đổi công thức `pricing_hash` giữa version sẽ khiến mọi Submit của session cũ
  trả `PRICE_CHANGED` — cần rollout có chủ đích.
- Thứ tự deploy với downstream: contract Promotion/Inventory/Product/Order phải
  sẵn sàng trước khi Checkout bật đường gọi tương ứng.
- Feature flag/canary chưa thiết kế.

## 10. Runbooks

### 10.1 Redis unavailable

Triệu chứng: Preview/Submit lỗi hạ tầng đồng loạt. Chẩn đoán: kiểm tra kết nối,
memory, latency Redis. Khắc phục: khôi phục Redis; sau khôi phục user Preview lại
(session mất). Xác nhận: tỷ lệ 2xx Preview phục hồi. Escalation: Checkout team +
platform.

### 10.2 `503`/`410` tăng ở Submit

Triệu chứng: Order call timeout liên tiếp; payload hết TTL. Chẩn đoán: kiểm tra
Order Service/Temporal health, latency; đối chiếu `checkout_token` với WorkflowID
bên Order để biết order đã tạo hay chưa. Khắc phục: khôi phục Order; client retry
sẽ dùng payload cache. Xác nhận: recovery thành công tăng, `503` giảm. Escalation:
Order team.

### 10.3 Degraded tăng

Triệu chứng: nhiều response `DEGRADED_PROMOTION`/`DEGRADED_STOCK`. Chẩn đoán:
latency/timeout Promotion calculation hoặc Inventory routing. Khắc phục: phía
downstream; user Preview lại sau khi hồi phục để bỏ cờ degraded. Xác nhận: tỷ lệ
degraded về mức nền.

### 10.4 `PRICE_CHANGED` bất thường

Triệu chứng: hash lệch hàng loạt. Chẩn đoán: có thay đổi giá/markdown diện rộng ở
Product, `promotion_campaign_revision` đổi, hoặc lệch công thức hash giữa
version Checkout (mixed deployment). Khắc phục: nếu do version, hoàn tất rollout
hoặc rollback; nếu do dữ liệu, hành vi là đúng thiết kế.

### 10.5 `PROMOTION_RECALCULATION_NEEDED` tăng

Triệu chứng: reserve fail hàng loạt (thường `BUDGET_EXHAUSTED` trong Flash Sale).
Chẩn đoán: budget campaign; Discovery budget guard có đang degraded không. Khắc
phục: phía Promotion; Checkout hoạt động đúng thiết kế. Xác nhận: tỷ lệ 409 giảm.

Runbook cho consumer lag, DLQ, cache corruption ngoài Redis, DB connection
exhaustion và reconciliation không áp dụng vì Checkout không có Kafka, DB hay
reconciliation job.

## 11. Workers và scheduled jobs

Không có. Cleanup toàn bộ dựa trên Redis TTL (session, payload, response, routing
cache, lock, rate-limit counter).
