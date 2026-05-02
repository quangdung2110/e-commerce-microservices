# Chat Service - Quy trình nghiệp vụ (Workflows)

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Database](chat_db_schema.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)

---
Tài liệu này mô tả luồng API/sự kiện từ lúc gửi tin nhắn đến các giai đoạn biên nhận/xử lý sự cố/lưu trữ với các cơ chế bảo vệ cần thiết.

## 1. Đường dẫn API cơ sở (Base API path)

`[POST/GET/PATCH] /api/v1/chat/...`

## 2. Quy trình gửi tin nhắn (Outbound)

**Endpoint:** `POST /api/v1/chat/rooms/{room_id}/messages`

1. Kiểm tra quyền gửi (`room_participants`).
2. Kiểm soát lặp lại đầu vào (Ingress idempotency):
   - Kiểm tra `ingress_idempotency_by_sender(sender_id, client_request_id)`.
   - Nếu đã tồn tại -> trả về tin nhắn đã tạo trước đó.
3. Sinh `message_id`, `bucket_ym`, cấp số thứ tự `message_seq`.
4. Ghi dữ liệu nghiệp vụ trong một đơn vị logic:
   - Chèn vào `messages_by_room_bucket`.
   - Chèn vào `message_by_id`.
   - Cập nhật (upsert) `ingress_idempotency_by_sender`.
5. Cập nhật Inbox theo chiến lược ưu tiên đọc (read-first strategy):
   - Đọc trạng thái cũ để lấy `old_last_message_at`.
   - Cập nhật `room_state_by_participant`.
   - Chèn dòng mới vào `inbox_index_by_participant_time`.
   - Xóa dòng cũ theo `old_last_message_at`.
6. Nếu là đa phương tiện/liên kết -> chèn vào `media_gallery_by_room`.
7. Ghi sự kiện outbox `MESSAGE_CREATED`.

> Lưu ý: API không xuất bản trực tiếp lên Kafka. Worker chuyển tiếp outbox (Outbox relay worker) sẽ thực hiện việc này và đánh dấu `PUBLISHED`.

## 3. Quy trình chuyển tiếp Outbox (Outbox relay)

1. Worker quét bảng `chat_outbox_events` với `status in (PENDING, FAILED)` và `next_retry_at <= now`.
2. Xuất bản theo bản đồ topic.
3. Thành công -> cập nhật `status=PUBLISHED`, `published_at`.
4. Thất bại -> tăng `retry_count`, đặt lại `next_retry_at` (theo cơ chế backoff), nếu vượt ngưỡng -> chuyển trạng thái `DEAD`.

## 4. Quy trình Biên nhận đã giao/đã đọc (UX-grade + bảo vệ phía máy chủ)

### 4.1. Đã giao (Delivered)

Client gửi xác nhận (ACK): `last_delivered_seq`.

Máy chủ phải kiểm tra bảo vệ:
1. Người tham gia phải thuộc phòng chat.
2. Phiên/Token xác thực hợp lệ.
3. `new_seq >= old_last_delivered_seq`.
4. `new_seq <= room_state.last_message_seq` (giới hạn nếu cần).

Nếu hợp lệ:
1. Cập nhật `room_watermarks.last_delivered_seq`.
2. Ghi outbox `MESSAGE_DELIVERED` (có thể gom nhóm - batch).

### 4.2. Đã đọc (Read)

**Endpoint:** `POST /api/v1/chat/rooms/{room_id}/read`

Client gửi `last_read_seq`.

Máy chủ phải kiểm tra bảo vệ tương tự quy trình "Đã giao":
1. Kiểm tra thành viên + xác thực.
2. Số thứ tự phải tăng dần.
3. Giới hạn theo `last_message_seq`.

Nếu hợp lệ:
1. Cập nhật `room_watermarks.last_read_seq`.
2. Cập nhật `room_state_by_participant.last_read_seq`.
3. Ghi outbox `MESSAGE_READ`.

Số tin chưa đọc (Unread) được tính:
1. `max(0, room_state.last_message_seq - room_state.last_read_seq)`.



## 7. Quy trình lưu giữ (Retention)

1. Chính sách giải quyết theo TTL mặc định cho từng loại hội thoại.
2. Dọn dẹp các nhóm thời gian (bucket) cũ trong ScyllaDB sau khi hết hạn.

## 8. Các kịch bản lỗi và Tự phục hồi (Self-healing)

1. Gửi lặp từ phía Client: chặn bằng tính lặp lại đầu vào (ingress idempotency).
2. Tiêu thụ sự kiện lặp: chặn bằng `chat_consumer_idempotency`.
3. Inbox index drift: worker thực hiện đối soát lại trạng thái và chỉ mục.
4. Lỗi chuyển tiếp Outbox: thực hiện thử lại (retry) + hàng đợi thư chết (DLQ).

## 9. Quy trình Trạng thái hoạt động (Presence Workflow)

1. Client gửi Heartbeat định kỳ (30-60s): `POST /api/v1/chat/presence/heartbeat`.
2. Hệ thống cập nhật Redis:
   - `SET presence:{user_id} {timestamp} EX 120`
   - `SET presence:{user_id}:last_seen {timestamp} EX 2592000` (30 ngày).
3. Nếu người dùng chuyển từ Offline sang Online: Ghi outbox `USER_PRESENCE_UPDATED`.

## 10. Quy trình Đẩy tin nhắn Real-time (WebSocket Delivery)

1. **WebSocket Gateway** tiêu thụ sự kiện từ Kafka topic `t.chat.events`.
2. Khi nhận sự kiện (ví dụ `MESSAGE_CREATED`):
   - Gateway lấy danh sách người tham gia trong `room_id`.
   - Với mỗi người tham gia (ngoại trừ người gửi):
     - Kiểm tra kết nối Socket cục bộ của Gateway.
     - Nếu tồn tại -> Đẩy (Push) dữ liệu tin nhắn qua Socket.
     - Nếu không tồn tại -> Gửi qua Firebase Cloud Messaging (FCM) hoặc bỏ qua.

## 11. Đề xuất SLO

1. Lưu trữ tin nhắn (Message persist) p95 < 30ms.
2. Đọc Inbox (Inbox read) p95 < 120ms.
3. Giao qua Websocket p95 < 150ms.
4. Độ trễ xuất bản outbox (Outbox publish lag) p95 < 3s.
