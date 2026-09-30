---
doc_id: payment-adr-013
service: payment
status: accepted
date: 2026-09-19
decision_owners:
  - Payment Service team
supersedes: payment-adr-009
evidence:
  - docs/payment/data.md
  - docs/order/data.md
---

# ADR-013 — Giữ toàn bộ transaction trong một OLTP schema; bỏ partition theo tháng, cold archive S3 và tiered read

## Trạng thái

`Accepted` 2026-09-19. Thay thế hoàn toàn
[ADR-009](./ADR-009-monthly-partitioning-and-tiered-archive.md). Chưa xác minh
implementation. Phần giữ financial row vô thời hạn được ADR-020 refine thành 7 năm
+ legal hold; quyết định một OLTP/no partition vẫn giữ nguyên.

## Bối cảnh

ADR-009 chọn `PARTITION BY RANGE (created_at)` cho `payment_transactions`, archive
partition > 6 tháng lên S3 và tiered read qua Athena. Khi rà lại schema cùng
[ADR-012](./ADR-012-recoverable-initiation-and-cancel.md), quyết định đó xung đột
với các guard tài chính bắt buộc:

- PostgreSQL yêu cầu mọi `PRIMARY KEY`/`UNIQUE` trên bảng partition phải chứa
  partition key. `UNIQUE (order_id, payment_attempt_number)` — guard chống
  double-initiate — và `UNIQUE (provider_transaction_id)` — guard chống hai
  transaction bind cùng một PaymentIntent — không còn enforce được toàn cục.
- `refund_transactions`, `provider_operations`, `payment_status_history` không thể
  FK về `payment_transactions(id)` đơn; phải FK theo `(id, created_at)` hoặc bỏ FK.
- Detach/drop partition xóa cả row còn liên quan tới refund/review case đang mở,
  trong khi refund và dispute có thể phát sinh nhiều tháng sau khi thanh toán.
- Tiered read tạo hai đường đọc (DB/S3) với hai profile độ trễ cho cùng một API,
  và thêm dependency S3/Athena chỉ để phục vụ tra cứu hiếm.

Order Service đã bỏ Cold Data Archiving từ Phase 16 với cùng lý do (một OLTP
schema, retention bằng policy thay vì bằng storage tier).

## Quyết định

- Mọi bảng Payment là bảng PostgreSQL thường, không partition. Uniqueness và FK
  được khai báo đầy đủ ở DB.
- Không có Archival CronJob, không có S3 archive bucket, không có tiered read.
  `WF-PAYMENT-010` (archive/tiered read) bị loại bỏ và số hiệu được dùng lại cho
  luồng review case; API read chỉ có một đường DB.
- Retention thực hiện bằng **row-level trimmer** theo bảng, không bằng drop
  partition (chi tiết ở `data.md §7`): `outbox_events` và `payment_idempotency`
  xóa theo watermark/`expires_at`; `webhook_logs` và `reconciliation_runs` xóa
  theo tuổi; financial transaction/operation/history/review giữ 7 năm + legal hold
  theo ADR-020, vẫn trong cùng OLTP và không tiered read.
- Hiệu năng scanner/poller giải quyết bằng **partial index** theo trạng thái
  đang hoạt động (`WHERE status IN (...)`) thay vì bằng partition pruning: tập
  row đang chờ luôn nhỏ bất kể tổng số row lịch sử.
- Schema giữ `created_at NOT NULL` trên mọi bảng để nếu volume thực tế vượt
  ngưỡng (ước lượng > 50 triệu row `payment_transactions`) có thể mở ADR mới
  cân nhắc lại partition với bảng registry riêng cho uniqueness.

## Phương án đã cân nhắc

- **Giữ ADR-009 nguyên trạng:** mất guard uniqueness toàn cục; FK phức tạp;
  archive cắt rời payment khỏi refund/dispute muộn. Không chọn.
- **Partition + bảng `payment_attempt_registry` không partition để enforce
  uniqueness:** giữ được guard nhưng thêm một bảng, hai lần insert, và FK vẫn
  phải trỏ qua registry. Chỉ đáng khi volume thực sự lớn. Không chọn ở giai đoạn
  này.
- **Một OLTP schema + partial index + row-level retention:** đơn giản, guard đầy
  đủ, nhất quán với Order. Được chọn.

## Hệ quả

Tích cực: uniqueness/FK enforce ở DB; một đường đọc; bớt archival job, S3, Athena,
tiered-read logic; nhất quán với Order. Tiêu cực: bảng `payment_transactions` và
`payment_status_history` tăng đơn điệu; cần theo dõi bloat/vacuum và kích thước
index; backup/restore cỡ lớn hơn theo thời gian.

## Ảnh hưởng migration

Migration đầu tiên tạo bảng thường (không có bước rename → partitioned → tạo
partition). Bỏ cấu hình `pg_partman`, S3 bucket/credential và job archival khỏi
`operations.md`; bỏ nhánh cold read khỏi `GET /v1/payments/{transactionId}`.
`README.md`, `architecture.md`, `operations.md`, `workflows.md`, `development.md` của
Payment đã được cập nhật cùng ADR này.

## Xác minh

Migration không chứa `PARTITION BY`; `UNIQUE (order_id, payment_attempt_number)`
và `UNIQUE (provider_transaction_id)` tồn tại trên `payment_transactions`; có
partial index cho scanner/poller/recovery queue; trimmer cho `outbox_events` chỉ
xóa row dưới CDC watermark; benchmark scanner với ≥ 10 triệu row lịch sử vẫn
chạy dưới ngưỡng thời gian đã đặt trong `operations.md`.
