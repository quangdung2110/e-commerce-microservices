---
doc_id: product-adr-004
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-004 — Pessimistic locking và lock order Product → SKU

## Status

`Accepted`

## Context

Nhiều luồng cùng ghi vào `products` và `product_skus`: admin API, Markdown Worker, nhánh
cascade `CATEGORY_DELETED`, Phase 2 của inventory sync và cronjob dọn markdown. Nếu hai
luồng lấy lock theo thứ tự ngược nhau, PostgreSQL sẽ báo deadlock và một transaction bị
hủy — hiện tượng này xuất hiện đúng vào lúc tải cao.

Ngoài ra, nhiều guard nghiệp vụ là kiểm tra rồi ghi (`SHOW` đọc `SUM(display_quantity)`
rồi đổi trạng thái; auto-reactivate đọc `admin_hidden` rồi đổi trạng thái). Kiểm tra
ngoài transaction tạo lỗi TOCTOU.

## Decision

Mọi transaction chạm nhiều bảng phải lấy lock theo đúng thứ tự: `products` trước bằng
`SELECT ... FOR NO KEY UPDATE`, rồi `product_skus` bằng `SELECT ... FOR UPDATE`.

Mọi guard quyết định state phải được đánh giá **bên trong** transaction, sau khi đã lock
row product.

Mọi worker chạm cùng một product phải đặt `lock_timeout` ngắn và nack/requeue khi
timeout, không được bỏ qua im lặng. Job quét diện rộng phải duyệt theo `product_id` tăng
dần và chia lô nhỏ, mỗi lô một transaction.

Job chạy theo lịch phải giữ distributed lock trong Redis để chỉ một instance quét một
tập dữ liệu tại một thời điểm.

Quyết định này áp dụng cho admin API, Markdown Worker, cascade category và cronjob.
Luồng inventory được xử lý riêng theo
[ADR-003](./ADR-003-two-phase-lock-free-inventory-sync.md); Phase 2 vẫn giữ đúng lock
order này nhưng chỉ lock một lần cho cả lô.

## Alternatives considered

- **Optimistic locking bằng cột `version` cho tất cả**: tránh giữ lock, nhưng với các
  batch lớn (markdown theo category) tỉ lệ xung đột và retry sẽ rất cao, và vẫn không
  giải quyết được TOCTOU của guard nếu không đọc lại trong transaction.
- **Serialize toàn bộ ghi qua một hàng đợi đơn luồng**: loại bỏ hoàn toàn deadlock,
  nhưng biến admin API thành bất đồng bộ và thêm một điểm nghẽn.
- **Không lock, dựa vào `updated_at`**: đủ cho việc chống ghi đè dữ liệu cũ ở downstream,
  nhưng không đủ cho các guard cần đọc-rồi-ghi nguyên tử.

## Consequences

Tích cực: không còn deadlock AB-BA giữa các luồng; guard nghiệp vụ đúng ngay cả khi có
tranh chấp; hành vi dễ suy luận khi debug.

Tiêu cực: throughput bị giới hạn bởi thời gian giữ lock trên các product hot; batch lớn
phải chia nhỏ nên tổng thời gian dài hơn; `lock_timeout` tạo ra lỗi tạm thời mà caller
và worker phải xử lý.

Ràng buộc mới: mọi code path mới chạm hai bảng phải tự giữ lock order — đây là quy tắc
không được framework kiểm tra hộ, nên phải có test và review bắt buộc.

## Migration impact

Không cần thay đổi schema. Ảnh hưởng tới cách viết repository và worker: các câu lệnh
lock phải tường minh chứ không dựa vào JPA sinh tự động. Cần cấu hình `lock_timeout` cho
worker và distributed lock cho cronjob.

## Verification

Chứng minh bằng: test tích hợp chạy song song admin API với Markdown Worker và với Phase
2 trên cùng một product; test cho guard `SHOW` và auto-reactivate dưới tranh chấp; và
metric số lần lock timeout cùng số deadlock do PostgreSQL báo cáo.
