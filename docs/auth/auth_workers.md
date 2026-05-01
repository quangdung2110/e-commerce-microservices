# Auth Service - Background Workers

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis & Caffeine)](auth_caching_schema.md)
*   [Database Schema](auth_db_design.md)
*   [Events Structure & Kafka Topics](auth_events.md)

---
Tài liệu này mô tả các Worker/Job chạy nền bên trong Auth Service để đảm bảo dọn dẹp hệ thống cũng như thực thi tính năng bảo mật xoay vòng chứng chỉ.

## 1. Dọn Dẹp Rác Token: `refresh-token-cleanup-cron`
Do mỗi lần Client gọi /refresh, một cặp token mới lại sinh ra và token cũ bị nằm lại DB ở trạng thái `revoked_at`. Sau một thời gian, bảng `refresh_tokens` sẽ bùng nổ dung lượng.

*   **Tính chất**: Scheduled Cron Job (Ví dụ: Chạy 1 lần vào lúc 2h sáng mỗi ngày).
*   **Logic**:
    1.  Khởi chạy Job `DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '7 days' OR (revoked_at IS NOT NULL AND revoked_at < NOW() - INTERVAL '7 days');`
    2.  `7 days` extra padding là để đảm bảo chúng ta không xóa một Token mới bị Revoked gần đây ra đi quá sớm (phòng hờ team Phân Tích Bảo Mật cần xem log / vết tích).
*   **Lưu ý Vận hành**: Do là Cronjob, cần đảm bảo chỉ `1` Pod trong cụm Auth Service thực thi Job này (Dùng ShedLock, Quartz hoặc Redisson Distributed Lock cho hàm Java/NodeJS đang chứa Annotation của Timer).

## 2. Quản Trị Hệ Khóa JSON Web Key Set: `key-rotation-worker`
Để đáp ứng theo chuẩn bảo mật, các cặp Private/Public Key dùng để ký/xuất JWT không nên cố định trọn đời. Hệ thống nên định kỳ đổi khóa (Ví dụ: 3 Tháng 1 lần). Tuy nhiên, vì người dùng cầm Access Token cũ vẫn còn sống 15 phút, ta không thể vứt ngay Key hiện tại mà cần Grace Period.

*   **Tính chất**: Scheduled Cron Job hoặc Trigger qua gRPC/Admin Endpoint.
*   **Logic**:
    1.  Keystore dùng bảng `jwt_keys` trong Auth DB làm nguồn gốc (có thể cache ở Redis để tăng tốc đọc).
    2.  Định kỳ sinh khóa mới Asymmetric. Set trạng thái `ACTIVE`.
    3.  Khóa cũ được chuyển trạng thái `PHASE_OUT` (Chỉ dùng để Verify chéo các token còn 15 phút cuối, KHÔNG dùng để Sign token cấp mới nữa).
    4.  Cặp Public Keys (JWKS endpoint) trả về sẽ map cả 2 `kid` (Key ID) cũ và mới. Gateway sẽ lấy được cả 2. Tự Gateway biết tra JWT header mang `kid` nào thì Verify bằng RSA key có `kid` ấy.
    5.  Worker phát tín hiệu nội bộ để **invalidate JWKS cache** ngay khi có key mới.
    6.  Cron sẽ xóa bỏ bản ghi Keystore của `PHASE_OUT` key sau khi đủ TTL rảnh tay (1 ngày an toàn).

## 3. Worker Outbox (Đã Lỗi Thời - Chuyển sang Debezium CDC)
Trong quá trình đăng ký (Register) hoặc Xác minh (Verify email):
*   **Hệ thống hiện tại sử dụng Debezium CDC Connector rà logs PostgreSQL (WAL)** để bắn event mượt mà cho Outbox Pattern. Vì vậy, trong mã nguồn Ứng dụng **không tồn tại** một `auth-outbox-relay` Worker định kỳ quét bảng `outbox_events` nhằm tiết kiệm chi phí Polling. Bảng `outbox_events` sẽ tự động dọn rác qua Trigger DB hoặc Cron tương đương.
*   **Khuyến nghị**: Dùng cron dọn rác rõ ràng (giữ 7-14 ngày) để tránh phình bảng outbox.

## 4. Dọn Dẹp Rác Audit Log: `audit-log-cleanup-cron`
Nhật ký của khách hàng (log_category = 'USER') sẽ rất lớn sau một thời gian, hệ thống cần tự động dọn dẹp để tiết kiệm dung lượng do chúng ta lưu thẳng vào bảng PostgreSQL.
*   **Tính chất**: Scheduled Cron Job (Ví dụ: Chạy 1 lần vào lúc 3h sáng mỗi ngày).
*   **Logic**:
    1.  Khởi chạy Job `DELETE FROM audit_logs WHERE log_category = 'USER' AND occurred_at < NOW() - INTERVAL '6 months';`

