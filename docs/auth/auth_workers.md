# Auth Service - Background Workers & Jobs

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Các worker dưới đây đảm bảo Auth Service vận hành ổn định khi tải cao, không chặn luồng API chính, và giữ nhất quán security state.

## 1. Outbox Relay Worker
**Mục đích**: phát event từ `auth_outbox_events` ra Kafka theo Transactional Outbox Pattern.

*   **Trigger**: chạy liên tục (poll 200-500ms).
*   **Workflow**:
    1.  Đọc batch row `status=PENDING`.
    2.  Publish lên `t.auth.events`.
    3.  ACK thành công -> `status=PUBLISHED`, set `published_at`.
    4.  Lỗi -> tăng `retry_count`, set `next_retry_at` theo exponential backoff.
    5.  Quá `max_retries` -> chuyển `status=FAILED` và đưa vào DLQ.

## 2. Expired Refresh Token Cleanup
**Mục đích**: dọn token hết hạn lâu ngày để giảm phình bảng.

*   **Schedule**: 02:00 AM hằng ngày.
*   **Logic**:
    1.  Xóa token có `expires_at < NOW() - INTERVAL '30 days'`.
    2.  Chỉ xóa token đã revoke hoặc hết hạn cứng.
    3.  Ghi audit job summary (rows deleted, duration).

## 3. Password Reset Session Cleanup
**Mục đích**: dọn reset session hết hạn và token one-time đã dùng.

*   **Schedule**: mỗi 15 phút.
*   **Logic**:
    1.  `UPDATE auth_password_reset_sessions SET state='EXPIRED'` với session quá hạn.
    2.  Xóa dữ liệu reset session đã `COMPLETED` quá 30 ngày.
    3.  Emit metric số lượng session expired/completed.

## 4. Inactive Session Archiver
**Mục đích**: đóng phiên bị bỏ lâu, giảm rủi ro account takeover.

*   **Schedule**: mỗi 12 giờ.
*   **Logic**:
    1.  Tìm `auth_device_sessions.is_active=true` và `last_active_at` quá 90 ngày.
    2.  Mark inactive + revoke refresh token liên quan.
    3.  Ghi `revoked_reason='INACTIVE_TIMEOUT'`.

## 5. Async Risk & Geo Analyzer Worker
**Mục đích**: chấm điểm rủi ro đăng nhập (không block API login đồng bộ).

*   **Trigger**: nhận message từ login flow (`OTP_LOGIN_VERIFIED`).
*   **Logic**:
    1.  So sánh IP/location hiện tại với phiên gần nhất.
    2.  Tính risk score (impossible travel, new device, ASN bất thường).
    3.  Nếu score cao -> emit `SUSPICIOUS_LOGIN_DETECTED`.
    4.  Nếu score rất cao -> đánh dấu account cần step-up verification ở lần đăng nhập sau.

## 6. Refresh Replay Escalation Worker
**Mục đích**: lớp dự phòng (safety-net) để bảo đảm replay grace period vẫn được escalated đúng khi API xử lý tức thời gặp race condition hoặc multi-node refresh.

*   **Schedule**: mỗi 1 phút.
*   **Logic**:
    1.  Quét token có `grace_expires_at IS NOT NULL`.
    2.  Nếu phát hiện trạng thái bất thường (`replay_incident_count > 1`) hoặc replay xuất hiện sau `grace_expires_at` mà chưa bị xử lý ở API:
        * revoke toàn bộ refresh token active của user,
        * đóng toàn bộ device sessions,
        * bump `session_version`,
        * emit `GLOBAL_LOGOUT_TRIGGERED` (reason=`REFRESH_REPLAY`).
    3.  Dọn grace state đã xử lý để tránh escalation lặp.

## 7. Audit Event Archiver
**Mục đích**: giữ bảng `auth_audit_events` gọn và vẫn đáp ứng compliance.

*   **Schedule**: mỗi ngày 03:00 AM.
*   **Logic**:
    1.  Archive bản ghi quá 2 năm sang object storage/SIEM.
    2.  Xóa dữ liệu đã archive thành công.
    3.  Gửi báo cáo archive vào monitoring.

## 8. Vận hành và cảnh báo
1.  Cảnh báo nếu Outbox `FAILED` tăng đột biến.
2.  Cảnh báo nếu `GLOBAL_LOGOUT_TRIGGERED` vượt ngưỡng bình thường.
3.  Cảnh báo nếu reset session `LOCKED` tăng mạnh (dấu hiệu tấn công).
4.  Cảnh báo nếu cleanup job chạy lỗi 3 lần liên tiếp.
