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
| `t.user.events` | `chat-user-privacy-sync` | User Service | `ACCOUNT_DELETED` | Hành động quyền riêng tư (xóa dữ liệu) |

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