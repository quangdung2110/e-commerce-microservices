---
doc_id: payment-adr-009
service: payment
status: superseded
date: null
superseded_by: payment-adr-013
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/data.md
  - docs/payment/workflows.md
---

# ADR-009 — Partition `payment_transactions` theo tháng, archive S3 sau 6 tháng, tiered read cho CSKH

## Trạng thái

`Superseded` bởi [ADR-013](./ADR-013-single-oltp-retention-no-partition.md) ngày
2026-09-19: bỏ partition, archive S3 và tiered read. Nội dung dưới đây giữ nguyên
làm lịch sử. Trước đó `Accepted` trong thiết kế; chưa xác minh implementation. Ngày không được ghi lại;
thiết kế cũ ghi nhận quyết định này vẫn giữ sau khi Order Service bỏ Cold Data
Archiving ở Phase 16.

## Bối cảnh

`payment_transactions` grow rất nhanh (mỗi lần user thử thanh toán = 1 record).
Expiration Scanner chạy mỗi 1–2 phút cần query nhanh; retention cho transaction
log phải lâu dài (> 6 tháng) phục vụ CSKH/audit. Order Service đã bỏ cơ chế Cold
Data Archiving (Phase 16) nhưng Payment có đặc thù retention riêng.

## Quyết định

- `payment_transactions` `PARTITION BY RANGE (created_at)` theo tháng; auto-create
  partition bằng `pg_partman` hoặc tạo thủ công. `refund_transactions`,
  `webhook_logs`, `payment_status_history` cũng nên partition theo `created_at`
  khi volume cao; ưu tiên `payment_transactions` trước vì lớn nhất.
- **Archival CronJob** weekly (Chủ nhật 3:00 AM): quét partition > 6 tháng;
  `pg_dump` → `s3://payment-archive/YYYY/MM/payment_transactions_YYYY_MM.sql.gz`;
  verify checksum SHA-256; `DETACH PARTITION`; DROP chỉ sau khi verify S3
  integrity pass; log archive event cho audit.
- **Tiered Read API:** check DB (partition hiện tại) trước; DB miss → query
  S3/Athena (hoặc tương đương scan Parquet/SQL trên S3), chậm ~2–5s chấp nhận
  được cho CSKH; response format giống nhau cho hot và cold data.
- Quyết định này **không** phụ thuộc pattern của Order.

## Phương án đã cân nhắc

- **Không partition, giữ toàn bộ trong một bảng:** scanner scan range lớn, index
  toàn cục phình. Không chọn.
- **Theo Order (bỏ archive):** Order không cần retention dài; Payment cần > 6
  tháng cho audit. Không chọn.
- **Partition + tiered storage:** scanner chỉ quét partition hiện tại (giảm
  10–50x), archive dễ, index nhỏ hơn. Được chọn.

## Hệ quả

Tích cực: hiệu năng scanner/operations hàng ngày; archive/drop đơn giản; chi phí
lưu trữ giảm. Tiêu cực: thêm archival job, S3/Athena dependency, tiered read
logic; query cross-partition phức tạp hơn; restore từ S3 khi cần.

## Ảnh hưởng migration

Migration rename bảng cũ → tạo bảng partitioned → tạo partition tháng; cần
`pg_partman` hoặc job tạo partition; cần S3 bucket + credential; API detail phải
có nhánh cold read.

## Xác minh

Cần migration partition, job archival với verify checksum trước drop, test tiered
read trả cùng format, metric archive success/fail.
