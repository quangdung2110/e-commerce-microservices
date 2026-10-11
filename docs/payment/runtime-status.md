---
doc_id: payment-runtime-status
service: payment
scope: current
status: implemented
owners:
  - Payment Service team
evidence:
  - java/services/payment-service/src/main/java/com/platform/paymentservice
  - java/services/payment-service/src/test/java/com/platform/paymentservice
  - contracts/http/payment/internal/v1/openapi.yaml
verified_against_commit: null
---

# Payment — Phạm vi runtime được kiểm chứng ngày 2026-10-11

Đây là bảng phân biệt implementation hiện tại và thiết kế vận hành mục tiêu.
`implemented` của service/contract không có nghĩa mọi yêu cầu production đã hoàn tất.
Chủ dự án chọn sửa lỗi runtime và đồng bộ tài liệu trong đợt audit này; KMS,
metric nghiệp vụ và heartbeat tự động được để lại cho đợt riêng.

## Hành vi hiện tại

| Phần | Hành vi và giới hạn |
|---|---|
| HTTP | 7 public/service operation, 9 Finance Ops operation. JSON từ chối field lạ, money dạng số thập phân, scalar sai kiểu. Lỗi parse/query parameter trả `400 VALIDATION_FAILED`; lỗi truy cập DB trả `503 DEPENDENCY_UNAVAILABLE`; đều Problem JSON và `private, no-store`. Refund `PAYMENT_NOT_SUCCESS`/`REFUND_AMOUNT_EXCEEDED` là HTTP 400. |
| Read tài chính | `GET /v1/payments/{id}` đọc payment/refund/disputes trong một snapshot `REPEATABLE READ` trên datasource primary; không trộn financial version cũ với refund mới. |
| Refund command | Metadata được JSON serialize, giữ nguyên opaque `refund_request_id`, kể cả dấu nháy/backslash/newline. Fingerprint provider được bind bằng JSON tree. |
| Lease | Token, owner và thời hạn đều phải hợp lệ lúc hoàn tất command/provider/inbox. Scanner recovery/expiration/webhook claim từng row ngay trước xử lý; batch là số lần xử lý tối đa, không giữ lease cho cả hàng chờ. Chưa tự gia hạn trong lúc chờ Stripe. Webhook lấy lại clock sau provider retrieve; response quá lease bị fence, recovery dùng đúng provider key/safety horizon. |
| Lane reconciliation | Session advisory lock PostgreSQL `(731024,1)` cho OBJECT_STATE, `(731024,2)` cho BALANCE_LEDGER. Session chết tự mất lock; lần kế tiếp đánh dấu run cũ `FAILED/OWNER_SESSION_LOST` rồi tạo run mới. Mỗi mutation giữ row lock của run, kiểm tra session sống và status RUNNING, cùng transaction với financial commit. Successor chờ mutation đã bắt đầu hoàn tất trước takeover. Provider I/O nằm ngoài transaction. Xem ADR-023. |
| Object state | Batch áp dụng riêng cho payment/refund/dispute: tối đa `3 × batch-size` candidate/run. Observation đổi thứ tự quét cho cả ba nhóm. FAILED tại DB và canceled tại Stripe là kết quả khớp. Refund SUCCESS được kiểm tra lại 35 ngày theo ADR-022. |
| Balance | Đọc hết các trang của một cửa sổ cố định, page size ≤100. Mỗi cửa sổ mới bắt đầu cursor null; `provider_cursor_from` trong DB chỉ là dấu vết run trước, không dùng làm Stripe cursor cho cửa sổ mới. Scheduler đọc bù từ watermark hoàn tất/cửa sổ thất bại; Finance Ops giữ nguyên cửa sổ explicit. Chỉ COMPLETED mới advance watermark. |
| Movement/ledger | Hỗ trợ charge/payment, refund/payment_refund, refund_failure, adjustment của dispute đã ánh xạ, stripe_fee, payout. Kiểm tra currency VND, amount−fee=net, dấu capture/refund/reversal/payout; fee credit đảo debit/credit. Canonical source được dùng chung với webhook/correction; duplicate phải cùng owner/type/amount/currency/lines và balance ID khi cả hai có ID. Journal cũ bất biến, không bổ sung balance ID bằng UPDATE. |
| Balance không thể xác minh | Source chưa ánh xạ, type/dấu không hỗ trợ, net sai, hoặc journal conflict → run FAILED với mã trong `error_message`; chưa tự mở review case, chưa so sánh closing balance/payout bank statement. Totals của run là movement đã đọc, gồm duplicate hợp lệ; refund reversal trừ refunded total, fee credit trừ fee total. |
| Freeze | Object lane vẫn quan sát/mở review nhưng không tự sửa FSM. Balance lane ghi `FAILED/AUTO_CORRECTION_FROZEN`, không gọi Stripe/ghi journal/advance watermark; scheduler đọc bù sau unfreeze. Freeze là cờ auto correction, không khóa webhook hoặc command của Order. |
| Manual correction | Maker-checker, case IN_PROGRESS được assign cho actor, đúng owner/amount/currency và FSM. JSON evidence là object được đưa vào canonical fingerprint; provider object ID tối đa 100, balance transaction ID tối đa 120 theo cột DB. FAILED yêu cầu `error_code`; CANCELED yêu cầu `reason_code`. Terminal non-capture yêu cầu `provider_status=canceled`, evidence `status=canceled`, `id` bằng stored PaymentIntent và `provider_object_id`. SUCCESS yêu cầu status=succeeded, evidence id bằng stored PaymentIntent và latest_charge bằng ch_ provider_object_id; Charge đã lưu nếu có cũng phải khớp. R1 refund vẫn tăng financial version của payment dù payment giữ status. |
| Giới hạn correction | Không hỗ trợ manual INITIATING→PENDING/bind client secret. Không có API tạo reversal journal để sửa amount; không được dùng SQL trực tiếp như một bước runbook đã triển khai. Evidence do Finance Ops cung cấp; đây không phải endpoint tự retrieve/verify chữ ký bằng chứng Stripe. |
| Retention | 7 năm financial, 90 ngày raw webhook, 12 tháng metadata/run, outbox ≥15 ngày dưới CDC watermark. Giữ control freeze mới nhất, balance watermark thành công mới nhất và idempotency còn provider operation REQUESTED/UNKNOWN. Legal hold dùng cùng thời điểm trimmer truyền vào. |

## Năng lực chưa triển khai

| Năng lực thiết kế | Trạng thái thực tế / công việc còn lại |
|---|---|
| KMS/Vault envelope adapter | Runtime dùng `LocalAesGcmSecretCipher` với `payment.crypto.local-key` và `local-key-id`. Chưa có provider KMS, `kms-key-id`/`previous-kms-key-ids` không được bind. Unit cipher hỗ trợ previous keys nhưng bean runtime chưa nạp chúng. Chưa có guard cấm local key trong profile prod. Không coi runbook rotation KMS là thao tác dùng được hiện nay. |
| Metric nghiệp vụ `payment_*`, dashboards/alerts | Chưa có instrumentation cho các tên trong operations §3–4. Micrometer/Actuator cơ bản có; không chứng minh SLO nghiệp vụ. |
| Heartbeat tự động | Port/JDBC hỗ trợ heartbeat, nhưng worker không gọi định kỳ. `payment.command.heartbeat`/`payment.webhook.heartbeat` chỉ là giá trị dự trù; không có tác dụng tự gia hạn. |
| Decline policy cấu hình | `max-per-attempt` có bind; danh sách non-retryable nằm trong `DeclinePolicy`, chưa có property `payment.stripe.decline.non-retryable-codes`. |
| Custom health / startup / shutdown | Chưa có health theo vai trò, `/internal/health/deps`, dependency fail-fast đầy đủ hoặc graceful drain riêng. Actuator probes mặc định không thay cho các khả năng này. |
| Privacy/export/DR tooling | DB có legal hold/export manifest và guard retention; chưa có API/job tự làm export, manifest verification, pseudonymization hay restore drill. RPO/RTO/capacity vẫn là target. |
| Rate limit và retry jitter | Chưa có replay rate limiter hay jitter cho DB retry. |
| Transport/RBAC | Service kiểm attribution/maker-checker/headers; TLS principal và quyền Finance/Accounting phải được mesh/identity layer bên ngoài bảo vệ. Chưa có RBAC theo role trong controller; không coi header actor tự khai là chứng minh quyền Accounting. |
| Kiểm chứng môi trường | Fake Stripe + PostgreSQL Testcontainers không chứng minh Sandbox, Kafka/CDC delivery thật, provider chaos, tải hoặc DR. Các gate này cần môi trường và credentials riêng. |

## Triển khai thay đổi ownership

Dừng reconciliation worker phiên bản cũ trước khi chạy bản ADR-023: code cũ không
tham gia advisory lock/fencing nên không thể rolling chung để bảo đảm singleton.
Các worker cùng phiên bản mới có thể chạy song song. Dự trù thêm một connection
autocommit mỗi lane đang chạy (tối đa hai/instance), ngoài connection transaction.
Không chạy session advisory lock qua PgBouncer transaction pooling; cần PostgreSQL
trực tiếp hoặc session pooling. Không tự timeout rồi xóa RUNNING khi owner vẫn sống.
Migration V3 bỏ CHECK không âm của refunded/fee reconciliation totals để hỗ trợ cửa sổ reversal/fee credit. Không đổi bảng hoặc journal cũ, không xóa dữ liệu tài chính.
