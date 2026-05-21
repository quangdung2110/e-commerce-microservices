# Chat Service - Cấu trúc Sự kiện (Events)

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Database](chat_db_schema.md)
* [Quy trình nghiệp vụ](chat_workflow.md)

---

## 1. Kafka Topics

### 1.1 Topics chính

```
Topic: chat.messages
- Số partition: 12
- Partition key: conversation_id (đảm bảo thứ tự trong conversation)
- Retention: 7 ngày
- Cleanup policy: delete

Topic: chat.message.received
- Số partition: 6
- Partition key: receiver_id (để cân bằng tải cho Notification Service)
- Retention: 1 ngày
- Cleanup policy: delete
- Mục đích: Gửi notification khi user offline
```

### 1.2 Consumer Groups

| Consumer Group | Instances | Topic | Mục đích |
|:---|:---|:---|:---|
| `ws-{hostname}` | N (1 per WS server) | chat.messages | Real-time broadcast |
| `msg-persister` | 2-4 | chat.messages | Persist vào Cassandra |
| `notification-chat-sync` | 2 | chat.message.received | Push/In-app notification |

---

## 2. Message Contracts

### 2.1 Chat Message (chat.messages)

```json
{
  "message_id": "msg_01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "conversation_id": "conv_abc123",
  "sender_id": "user_xyz",
  "sender_name": "Nguyen Van A",
  "type": "TEXT",
  "content": "Xin chào mọi người",
  "created_at": 1716000000000,
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "reply_to": null,
  "metadata": {}
}
```

### 2.2 Message Types

| Type | Mô tả |
|------|-------|
| TEXT | Tin nhắn text thông thường |
| IMAGE | Tin nhắn chứa hình ảnh |
| FILE | Tin nhắn chứa file đính kèm |
| SYSTEM | Tin nhắn hệ thống |

### 2.3 Chat Message Received Event (chat.message.received)

Event được produce bởi Chat Service sau khi message được persist thành công, dành cho Notification Service.

```json
{
  "specversion": "1.0",
  "id": "evt_01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "source": "/chat-service/messages",
  "type": "CHAT_MESSAGE_RECEIVED",
  "time": "2026-05-20T10:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "message_id": "msg_01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "conversation_id": "conv_abc123",
    "conversation_name": "Nhóm A",
    "conversation_type": "GROUP",
    "sender_id": "user_xyz",
    "sender_name": "Nguyen Van A",
    "message_type": "TEXT",
    "message_preview": "Xin chào mọi người",
    "receiver_ids": ["user_123", "user_456", "user_789"],
    "created_at": 1716000000000
  }
}
```

---

## 3. Kafka Configuration

### 3.1 Producer Configuration (Chat API)

```yaml
producer:
  acks: all                           # Đảm bảo durability
  retries: 3                          # Retry khi fail
  linger.ms: 5                        # Batch for throughput
  compression.type: lz4               # Fast compression
  enable.idempotence: true            # Exactly-once semantics
```

### 3.2 Consumer Configuration

```yaml
# WebSocket Gateway
consumer:
  group.id: ws-{hostname}
  auto.offset.reset: latest           # Chỉ nhận message mới
  enable.auto.commit: true
  auto.commit.interval.ms: 1000      # Commit sau 1 giây

# Message Persister
consumer:
  group.id: msg-persister
  auto.offset.reset: earliest         # Replay từ đầu nếu cần
  enable.auto.commit: true
  auto.commit.interval.ms: 5000

# Notification Service (Chat consumer)
consumer:
  group.id: notification-chat-sync
  auto.offset.reset: latest
  enable.auto.commit: true
```

---

## 4. Consumer Groups Chi tiết

### 4.1 WebSocket Gateway Consumer

```
Group ID: ws-{instance-id}
Instances: N (1 per WebSocket server)

Responsibilities:
- Subscribe chat.messages
- Broadcast đến connected clients
- Không cần deduplication (client dedup)

Configuration:
- auto.offset.reset: latest
- enable.auto.commit: true (1s interval)
```

### 4.2 Message Persister Consumer

```
Group ID: msg-persister
Instances: 2-4 (scale theo partition)

Responsibilities:
- Subscribe chat.messages
- Persist messages vào Cassandra
- Idempotent write (check message_id)
- Produce event CHAT_MESSAGE_RECEIVED vào topic chat.message.received

Configuration:
- auto.offset.reset: earliest
- enable.auto.commit: true (5s interval)

Failure Handling:
- Nếu Cassandra write fail → retry với backoff
- Nếu exceed max retries → log error, commit offset (chấp nhận message loss)
```

### 4.3 Notification Service Consumer

```
Group ID: notification-chat-sync
Instances: 2

Responsibilities:
- Subscribe chat.message.received
- Check presence của recipients
- Send push notification khi offline
- Send in-app notification

Configuration:
- auto.offset.reset: latest
- enable.auto.commit: true

Logic:
- Get receiver_ids từ message
- Check Redis presence: EXISTS user:presence:{userId}
- Nếu offline → send FCM/APNs

Tích hợp với Notification Service:
- Sử dụng user preferences chung (opt-out, quiet hours)
- Template rendering qua notification service
- Delivery logs tập trung
```

---

## 5. Delivery Semantics

### 5.1 At-Least-Once

- Kafka đảm bảo at-least-once delivery
- Message có thể được deliver nhiều lần
- Client và Consumer phải handle duplicate

### 5.2 Idempotency Handling

**Consumer-side:**
- Message Persister: check `message_id` trước khi insert
- WebSocket Gateway: không cần check (client dedup)
- Notification Service: dùng `event_id` trong CloudEvents để deduplicate

---

## 6. Partitioning Strategy

### 6.1 chat.messages

```
Key: conversation_id
```

**Benefits:**
- Tất cả messages trong cùng conversation vào cùng partition
- Kafka đảm bảo thứ tự trong partition
- Consumer đọc theo offset đúng thứ tự

**Considerations:**
- Không tăng partition count sau khi đã produce (sẽ change partition assignment)
- Hot conversation có thể là bottleneck (nhiều messages vào 1 partition)

### 6.2 chat.message.received

```
Key: receiver_id
```

**Benefits:**
- Cân bằng tải cho Notification Service
- Mỗi instance xử lý một tập user_ids riêng

---

## 7. Event Sequence

### 7.1 Ordering

Thứ tự được đảm bảo trong cùng conversation qua Kafka partition.

### 7.2 Sequence ID

Có thể thêm sequence number trong message để detect missing messages:

```json
{
  "message_id": "msg_xxx",
  "sequence": 1024,
  "conversation_id": "conv_abc",
  ...
}
```

Client có thể detect gap và request resync.

---

## 8. Retry và Error Handling

### 8.1 Producer Retry

```
- retries: 3
- retry.backoff.ms: 100
- Nếu fail sau retries → return error cho client
- Client sẽ retry với same idempotency_key
```

### 8.2 Consumer Error Handling

```
- Transient error (timeout, connection) → retry với backoff
- Permanent error (invalid data) → log + skip
- Nếu exceed max retries → log error + commit offset
```

---

## 9. Đường dẫn tài liệu

- [Thiết kế kiến trúc](chat_design.md)
- [Cấu trúc Database](chat_db_schema.md)
- [Quy trình nghiệp vụ](chat_workflow.md)
- [Notification Service Events](../notification/events.md) - Event contracts của Notification Service
