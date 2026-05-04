# Chat Service - Cấu trúc Sự kiện (Events)

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Database](chat_db_schema.md)
* [Quy trình nghiệp vụ (Workflows)](chat_workflow.md)

---
Tài liệu này định nghĩa hợp đồng sự kiện (event contract) cho Chat Service theo hướng contract-first, đầy đủ payload cho các sự kiện đã khai báo.

## 1. Bản đồ Kafka topics

### 1.1. Các Topics xuất bản (Produce)

| Tên Topic | Partition Key | Các loại sự kiện | Người tiêu thụ (Consumers) |
|:---|:---|:---|:---|
| `t.chat.events` | `room_id` | `MESSAGE_CREATED`, `MESSAGE_REVOKED`, `MESSAGE_DELIVERED`, `MESSAGE_READ`,`CONVERSATION_STATE_CHANGED` | Notification, Analytics, các worker nội bộ |

### 1.2. Các Topics tiêu thụ (Consume)

| Tên Topic | Consumer Group | Nhà sản xuất (Producer) | Các loại sự kiện | Mục đích |
|:---|:---|:---|:---|:---|
| `t.user.events` | `chat-user-sync` | User Service | `USER_PROFILE_UPDATED`, `USER_PROFILE_DELETED` | Đồng bộ tên hiển thị & GDPR cleanup |

## 2. CloudEvents envelope (Bắt buộc)

```json
{
  "event_id": "uuid-v7",
  "event_type": "MESSAGE_CREATED",
  "event_schema_version": 1,
  "aggregate_type": "CHAT_ROOM",
  "aggregate_id": "room-uuid",
  "correlation_id": "request-or-workflow-id",
  "timestamp": "2026-03-31T10:00:00Z",
  "source": "chat-service",
  "state_version": 128,
  "event_sequence_id": 1743415200123,
  "payload": {}
}
```

## 3. Ngữ nghĩa phân phối (Delivery semantics)

1. Mô hình phân phối: At-Least-Once (Ít nhất một lần).
2. Nhà sản xuất dùng outbox relay (không xuất bản trực tiếp sau khi ghi logic nghiệp vụ).
3. Người tiêu thụ bắt buộc phải có tính lặp lại (idempotent) theo `event_id`.
4. Bảo vệ chống sai lệch thứ tự bằng `event_sequence_id` hoặc `state_version`.

## 4. Hợp đồng các sự kiện xuất bản (Produced events contract)

### 4.1. MESSAGE_CREATED (Tin nhắn được tạo)

```json
{
  "room_id": "uuid",
  "conversation_type": "USER_TO_USER",
  "message_id": "uuid",
  "message_seq": 1024,
  "created_at": "2026-03-31T10:00:00Z",
  "sender_role": "USER",
  "sender_id": "uuid",
  "msg_type": "TEXT",
  "metadata_visibility": "PUBLIC"
}
```

### 4.2. MESSAGE_REVOKED (Tin nhắn bị thu hồi)

```json
{
  "room_id": "uuid",
  "message_id": "uuid",
  "message_seq": 1024,
  "revoked_by": "uuid",
  "revoked_at": "2026-03-31T10:03:00Z",
  "reason": "USER_ACTION"
}
```

### 4.3. MESSAGE_DELIVERED (Tin nhắn đã được giao)

```json
{
  "room_id": "uuid",
  "participant_id": "uuid",
  "last_delivered_seq": 1024,
  "delivered_at": "2026-03-31T10:01:00Z"
}
```

### 4.4. MESSAGE_READ (Tin nhắn đã được đọc)

```json
{
  "room_id": "uuid",
  "participant_id": "uuid",
  "last_read_seq": 1024,
  "last_read_at": "2026-03-31T10:01:20Z"
}
```

### 4.5. CONVERSATION_STATE_CHANGED (Trạng thái hội thoại thay đổi)

```json
{
  "room_id": "uuid",
  "from_state": "OPEN",
  "to_state": "CLOSED",
  "changed_by": "USER",
  "changed_at": "2026-03-31T10:01:40Z"
}
```

### 4.6. USER_PRESENCE_UPDATED (Trạng thái hoạt động thay đổi)

```json
{
  "user_id": "uuid",
  "status": "online",
  "last_seen_at": "2026-03-31T10:01:40Z"
}
```

## 5. Hợp đồng các sự kiện tiêu thụ (Consumed events contract)

### 5.1. USER_PROFILE_UPDATED (Từ User Service)

Chat Service subscribe event này để cập nhật projection `sender_name` trong bảng `room_participants`, giúp hiển thị tên người gửi mà không cần gọi User Service mỗi lần.

```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "uuid-cua-user",
  "partition_key": "uuid-cua-user",
  "event_type": "USER_PROFILE_UPDATED",
  "timestamp": "2026-04-22T08:05:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "email": "user@example.com",
    "first_name": "Nguyen",
    "last_name": "Quang Dung",
    "phone_number": "0987654321",
    "date_of_birth": "1997-10-19",
    "gender": "MALE"
  }
}
```

**Xử lý tại Chat Service:**
1. Consumer nhận event `USER_PROFILE_UPDATED`
2. Kiểm tra idempotency qua `event_id` trong bảng `chat_consumer_idempotency`
3. Cập nhật `sender_name` trong các bảng:
   - `room_participants` (cập nhật `participant_display_name`)
   - `messages_by_room_bucket` (cập nhật `sender_name` cho messages mới chưa có)

### 5.2. USER_PROFILE_DELETED (Từ User Service)

Chat Service subscribe event này để xóa vĩnh viễn PII của user đã bị xóa (GDPR compliance).

```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "uuid-cua-user",
  "partition_key": "uuid-cua-user",
  "event_type": "USER_PROFILE_DELETED",
  "timestamp": "2026-04-22T08:15:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "email": "user@example.com",
    "reason": "GDPR_REQUEST"
  }
}
```

**Xử lý tại Chat Service:**
1. Consumer nhận event `USER_PROFILE_DELETED`
2. Kiểm tra idempotency qua `event_id`
3. Xóa tất cả dữ liệu liên quan đến `user_id`:
   - `room_participants` (xóa participant)
   - `room_state_by_participant` (xóa trạng thái inbox)
   - `inbox_index_by_participant_time` (xóa chỉ mục)
   - `presence:{user_id}` (xóa Redis presence)
4. Anonymize messages (thay `sender_name` = "User đã xóa tài khoản")