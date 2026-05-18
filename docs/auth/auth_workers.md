# Auth Service - Background Workers

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis)](auth_caching_schema.md)
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

## 2. Key Rotation Worker: `key-rotation-worker`
Để đáp ứng theo chuẩn bảo mật, các cặp Private/Public Key dùng để ký/xuất JWT không nên cố định trọn đời. Hệ thống nên định kỳ đổi khóa (Ví dụ: 3 Tháng 1 lần). Tuy nhiên, vì người dùng cầm Access Token cũ vẫn còn sống 15 phút, ta không thể vứt ngay Key hiện tại mà cần Grace Period.

*   **Tính chất**: Scheduled Cron Job hoặc Trigger qua gRPC/Admin Endpoint.
*   **Chu kỳ**: Mỗi 30 ngày (configurable).
*   **Logic**:
    1.  Keystore dùng bảng `jwt_keys` trong Auth DB làm nguồn gốc (có thể cache ở Redis để tăng tốc độ đọc).
    2.  Tạo key mới Asymmetric. Set trạng thái `ACTIVE`.
    3.  Khóa cũ (đang `ACTIVE`) được chuyển trạng thái `PHASE_OUT`, set `rotated_at = NOW()`.
    4.  Cặp Public Keys (JWKS endpoint) trả về sẽ map cả 2 `kid` (Key ID) cũ và mới. Gateway sẽ lấy được cả 2.
    5.  **Publish Notification**: Sau khi cập nhật DB thành công, worker thực hiện:
        *   Xóa cache nội bộ của Auth Service.
        *   Publish message lên Redis Pub/Sub channel `auth:jwks:update` với nội dung `{"event":"JWKS_UPDATED", "timestamp": now}`.

## 2.1 Key Retirement Worker: `key-retirement-worker`
Worker này chạy thường xuyên hơn để dọn dẹp các key đã hết Grace Period. Sau khi key bị `PHASE_OUT`, nó vẫn được giữ lại trong JWKS để verify các token cũ. Tuy nhiên, sau khi tất cả token có thể sử dụng key đó đã hết hạn (Access Token 15 phút + Refresh Token tối đa 30 ngày), key cần được chuyển sang `RETIRED` và xóa khỏi JWKS.

*   **Tính chất**: Scheduled Cron Job, chạy thường xuyên hơn rotation worker.
*   **Chu kỳ**: Mỗi giờ (configurable, có thể `0 * * * *`).
*   **Grace Period Logic**:
    *   Key được giữ ở trạng thái `PHASE_OUT` trong khoảng thời gian `max_token_lifetime`.
    *   `max_token_lifetime` = Thời gian sống tối đa của Refresh Token (mặc định 30 ngày) + buffer 1 ngày = 31 ngày.
    *   Key có thể retire khi: `rotated_at + INTERVAL '31 days' <= NOW()`.
*   **Logic**:
    1.  Query các key có `status = 'PHASE_OUT'` và `rotated_at + INTERVAL '<max_token_lifetime>' <= NOW()`.
    2.  Với mỗi key đủ điều kiện:
        *   Update DB: `UPDATE jwt_keys SET status = 'RETIRED' WHERE kid = ?`.
        *   Ghi log `KEY_RETIRED` vào `audit_logs` với `actor_type = 'SYSTEM'`.
    3.  Sau khi update DB thành công:
        *   Xóa cache JWKS nội bộ của Auth Service.
        *   Publish message lên Redis Pub/Sub channel `auth:jwks:update` để Gateway clear cache.
*   **Lưu ý Vận hành**:
    *   Sử dụng Distributed Lock (Redis-based) để tránh nhiều pods chạy cùng lúc.
    *   Xử lý batch nhỏ (LIMIT 50) mỗi lần chạy để tránh quá tải database.
    *   Không xóa vĩnh viễn key (không DELETE row), chỉ chuyển sang `RETIRED` để phục vụ audit/security review nếu cần.

## 3. Worker Outbox (Đã Lỗi Thời - Chuyển sang Debezium CDC)
Trong quá trình đăng ký (Register) hoặc Xác minh (Verify email):
*   **Hệ thống hiện tại sử dụng Debezium CDC Connector rà logs PostgreSQL (WAL)** để bắn event mượt mà cho Outbox Pattern. Vì vậy, trong mã nguồn Ứng dụng **không tồn tại** một `auth-outbox-relay` Worker định kỳ quét bảng `outbox_events` nhằm tiết kiệm chi phí Polling. Bảng `outbox_events` sẽ tự động dọn rác qua Trigger DB hoặc Cron tương đương.
*   **Khuyến nghị**: Dùng cron dọn rác rõ ràng (giữ 7 ngày) để tránh phình bảng outbox.

## 4. Dọn Dẹp Rác Audit Log: `audit-log-cleanup-cron`
Nhật ký của khách hàng (log_category = 'USER') sẽ rất lớn sau một thời gian, hệ thống cần tự động dọn dẹp để tiết kiệm dung lượng do chúng ta lưu thẳng vào bảng PostgreSQL.
*   **Tính chất**: Scheduled Cron Job (Ví dụ: Chạy 1 lần vào lúc 3h sáng mỗi ngày).
*   **Logic**:
    1.  Khởi chạy Job `DELETE FROM audit_logs WHERE log_category = 'USER' AND occurred_at < NOW() - INTERVAL '6 months';`

## 5. Worker Tự Động Mở Khóa Tài Khoản: `auto-unlock-account-worker`
Khi tài khoản bị `LOCKED` do đăng nhập sai mật khẩu quá nhiều lần, hệ thống sẽ tự động mở khóa sau **30 phút** để user có thể thử lại.
*   **Tính chất**: Scheduled Cron Job (Ví dụ: Chạy mỗi 5 phút một lần để kiểm tra).
*   **Logic**:
    1.  Query tất cả user có `status = 'LOCKED'` và `locked_at <= NOW() - INTERVAL '30 minutes'`.
    2.  Với mỗi user đủ điều kiện:
        *   Update DB: `UPDATE users SET status = 'ACTIVE', locked_at = NULL, failed_login_attempts = 0 WHERE id = ?`
        *   Gửi Kafka Event `USER_UNLOCKED` cho Notification Service để thông báo qua **Email** cho user.
        *   Ghi log `USER_AUTO_UNLOCKED` vào `audit_logs` với `actor_type = 'SYSTEM'`.
*   **Lưu ý Vận hành**: 
    *   Chỉ xử lý batch nhỏ (LIMIT 100) mỗi lần chạy để tránh quá tải database.
    *   Không tự động unlock tài khoản `BANNED` - tài khoản BANNED chỉ được Admin mở khóa thủ công.
