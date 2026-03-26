# Auth Service - Background Workers & Jobs

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Tài liệu này mô tả các tiến trình chạy ngầm (Background Tasks/CronJobs) bên trong Auth Service nhằm dọn dẹp dữ liệu rác, xử lý bất đồng bộ, và phân tích rủi ro mà không làm ảnh hưởng tới độ trễ (Latency) của Login/Register API.

## 1. Relational Outbox Worker (Producer)
**Mục đích**: Hiện thực Transactional Outbox Pattern kết hợp với bảng trung gian `outbox_events` (hoặc Caching Outbox) để đảm bảo 100% tính nhất quán.

*   **Trigger**: Chạy liên tục dưới dạng Background Thread (Debezium Change Data Capture / Async Polling theo mili-giây).
*   **Workflow**:
    1.  Ứng dụng Insert Database vào bảng `auth_identities` đồng thời Insert vào `outbox_events` trong cùng một DB Transaction khi "Đăng ký thành công".
    2.  Worker quét các hàng trong `outbox_events` có `status = PENDING`.
    3.  Đẩy Payload lên **Kafka** (topic: `t.auth.events`).
    4.  Nhận ACK (Thành công) từ Kafka Brokers -> Cập nhật `outbox_events.status = PUBLISHED` (Hoặc xoá luôn row).

## 2. Expired Token Cleanup (CronJob)
**Mục đích**: Bảng `auth_refresh_tokens` trên PostgreSQL sẽ phình to rất nhanh với mỗi lần User đăng nhập/đăng xuất/hết hạn. Gây lãng phí ổ đĩa cứng.

*   **Lịch chạy (Schedule)**: 02:00 AM hằng ngày (Off-peak hour).
*   **Logic**:
    1.  Tìm tất cả các `auth_refresh_tokens` có trường `expires_at` đã quá hạn `> 30 ngày`.
    2.  Xóa bằng lệnh: `DELETE FROM auth_refresh_tokens WHERE expires_at < NOW() - INTERVAL '30 DAYS'`.
    3.  (Lưu ý không xoá Token vừa mới hết hạn hôm qua, giữ bù khoảng thời gian ngắn để query phân tích log nếu cần thiết).

## 3. Inactive Session Archiver (CronJob)
**Mục đích**: Tự động "Kick" (Đăng xuất) các phiên làm việc (Thiết bị) đã bị bỏ xó quá lâu giúp an toàn bảo mật.

*   **Lịch chạy (Schedule)**: Mỗi 12 giờ một lần.
*   **Logic**:
    1.  Tìm kiếm trong `auth_device_sessions` những phiên `is_active = TRUE` nhưng `last_active_at` đã trôi qua hơn 90 ngày (hoặc tuỳ policy cấu hình).
    2.  Cập nhật trạng thái phiên: `UPDATE auth_device_sessions SET is_active = FALSE WHERE ...`.
    3.  Chuyển Refresh Token đính kèm thành `is_revoked = TRUE`.

## 4. Async Risk & Geo-Analyzer Worker
**Mục đích**: Phân tích rủi ro đăng nhập dị thường (Suspicious Login) trên Background thay vì chặn luồng chính lúc Login.

*   **Trigger**: Lắng nghe Message đẩy qua Channel/Go Routine hoặc MQ ảo nội bộ khi API Login được gọi.
*   **Logic (Impossible Travel Analysis)**:
    1.  Nhận sự kiện chứa `(user_id, IP_hiện_tại, Tọa_độ_mới, Thời_gian_hiện_tại)`.
    2.  Truy vấn phiên gần nhất trong Redis/DB `(IP_cũ, Tọa_độ_cũ, Thời_gian_cũ)`.
    3.  Nếu IP_cũ là Việt Nam, IP_mới là Mỹ. Mà hiệu số Thời\_gian\_hiện\_tại trừ Thời\_gian\_cũ chỉ là < 5 giờ. => Kết luận "Vô lý về mặt địa lý" (Impossible Travel).
    4.  Bắn Kafka Event: `SUSPICIOUS_LOGIN_DETECTED`. (Để hệ thống tự động khóa Account hoặc Gửi Email xác minh cảnh báo khách hàng).
    5.  Thành phần này có thể gọi IP to Geolocation API ở Caching hoặc Local db (GeoLite2).
