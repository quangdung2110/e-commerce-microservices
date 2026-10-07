# Chat Service - Cấu trúc Database

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)
* [Quy trình nghiệp vụ](chat_workflow.md)

---

## 1. Tổng quan

Hệ thống sử dụng 3 storage chính:

| Storage | Mục đích |
|---------|-----------|
| **Redis** | Idempotency cache, Presence, Rate Limiting, Hot Cache (optional) |
| **ScyllaDB/Cassandra** | Messages history, Room state, Inbox index |
| **S3** | File attachments, Media files |

---

## 2. Redis Schema

### 2.1 Idempotency Cache

```
Purpose: Ngăn chặn duplicate message submission

Key Pattern: idem:{conversation_id}:{sender_id}:{idempotency_key}
Type: String
Value: message_id đã tạo hoặc "PENDING"
TTL: 86400 seconds (24 giờ)

Operation:
1. SET key <PENDING> NX EX 86400
2. Produce Kafka
3. SET key {message_id} EX 86400

Example:
SET idem:conv123:user456:550e8400-e29b-41d4-a716-446655440000 "PENDING" NX EX 86400
```

### 2.2 Presence

```
Purpose: Theo dõi trạng thái online/offline của user

Key Pattern: user:presence:{user_id}
Type: String
Value: "online"
TTL: 60 seconds (refreshed by heartbeat)

Operation:
- Online: SET user:presence:{userId} "online" EX 60
- Heartbeat: SET user:presence:{userId} "online" EX 60
- Offline: DEL user:presence:{userId}

Check:
EXISTS user:presence:{userId}
→ 1: online
→ 0: offline
```

### 2.3 Rate Limiting

```
Purpose: Giới hạn số lượng message được gửi

Key Pattern: ratelimit:msg:{user_id}
Type: Sorted Set
Score: timestamp (milliseconds)
Member: request_id
TTL: 60 seconds

Operation:
ZADD ratelimit:msg:{userId} {timestamp} {requestId}
ZREMRANGEBYSCORE ratelimit:msg:{userId} 0 {now - 60000}
ZCARD ratelimit:msg:{userId}
IF count > 60 → reject

Example:
ZADD ratelimit:msg:user123 1716000000000 "req_001"
ZREMRANGEBYSCORE ratelimit:msg:user123 0 1715999940000
ZCARD ratelimit:msg:user123
```

### 2.4 Hot Cache (Optional)

```
Purpose: Cache last N messages để giảm tải Cassandra khi sync

Key Pattern: room_hot:{conversation_id}
Type: Sorted Set
Score: message_id (Snowflake, sortable)
Member: JSON serialized message
Size: Last 50 messages
TTL: 3600 seconds (1 giờ)

Operation:
ZADD room_hot:{convId} {message_id} {message_json}
ZREMRANGEBYRANK room_hot:{convId} 0 -51  // Keep only 50
ZREVRANGE room_hot:{convId} 0 49          // Get last 50

Usage:
- Khi client sync sau reconnect
- Ưu tiên đọc từ Redis trước
- Fallback sang Cassandra nếu miss
```

---

## 3. ScyllaDB Schema

### 3.1 messages (Bảng chính lưu tin nhắn)

```sql
CREATE TABLE chat.messages (
  conversation_id text,
  message_id text,          -- Snowflake ID (sortable)
  sender_id text,
  sender_name text,        -- Projection từ User; anonymize khi nhận Auth USER_DELETED
  type text,
  content text,
  created_at timestamp,
  reply_to text,
  metadata map<text, text>,
  PRIMARY KEY (conversation_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

### 3.2 conversations (Bảng conversation)

```sql
CREATE TABLE chat.conversations (
  conversation_id text PRIMARY KEY,
  conversation_type text,
  created_at timestamp,
  updated_at timestamp,
  status text
);
```

### 3.3 conversation_participants (Người tham gia)

```sql
CREATE TABLE chat.conversation_participants (
  conversation_id text,
  user_id text,
  joined_at timestamp,
  PRIMARY KEY (conversation_id, user_id)
);
```

### 3.4 inbox_index (Chỉ mục inbox)

```sql
CREATE TABLE chat.inbox_index (
  user_id text,
  last_message_at timestamp,
  conversation_id text,
  last_message_preview text,
  unread_count int,
  PRIMARY KEY (user_id, last_message_at, conversation_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC);
```

### 3.5 user_presence (Optional - nếu dùng DB thay vì Redis)

```sql
CREATE TABLE chat.user_presence (
  user_id text PRIMARY KEY,
  status text,
  last_seen_at timestamp,
  device_info map<text, text>
);
```

---

## 4. Indexes

```sql
-- Index để query messages theo sender
CREATE INDEX idx_messages_sender ON chat.messages (sender_id);

-- Index để query inbox theo user
CREATE INDEX idx_inbox_user ON chat.inbox_index (user_id);

-- Index để query participants theo user
CREATE INDEX idx_participants_user ON chat.conversation_participants (user_id);
```

---

## 5. Data Retention

```sql
-- Messages retention: 6 tháng (tùy policy)
-- Có thể dùng TTL hoặc scheduled job để delete

-- Inbox cleanup
DELETE FROM chat.inbox_index
WHERE user_id = ? AND last_message_at < ?;
```

---

## 6. Example Queries

### 6.1 Get Messages in Conversation

```sql
-- Get last 50 messages
SELECT * FROM chat.messages
WHERE conversation_id = 'conv123'
ORDER BY message_id DESC
LIMIT 50;

-- Get messages before a message (pagination)
SELECT * FROM chat.messages
WHERE conversation_id = 'conv123'
  AND message_id < 'msg_xxx'
ORDER BY message_id DESC
LIMIT 50;
```

### 6.2 Get Inbox

```sql
-- Get user's conversations
SELECT * FROM chat.inbox_index
WHERE user_id = 'user123'
ORDER BY last_message_at DESC
LIMIT 20;
```

### 6.3 Get Conversation Participants

```sql
-- Get all participants
SELECT * FROM chat.conversation_participants
WHERE conversation_id = 'conv123';
```

---

## 7. S3 Schema (File Storage)

### 7.1 File Metadata Table

```sql
CREATE TABLE chat.file_metadata (
  file_id text PRIMARY KEY,
  conversation_id text,
  uploader_id text,
  file_name text,
  file_type text,
  file_size bigint,
  s3_key text,
  uploaded_at timestamp,
  PRIMARY KEY (conversation_id, uploaded_at)
);
```

### 7.2 S3 Object Path Convention

```
s3://chat-attachments/{conversation_id}/{file_id}/{filename}
```

---

## 8. Đường dẫn tài liệu

- [Thiết kế kiến trúc](chat_design.md)
- [Cấu trúc Sự kiện](chat_events.md)
- [Quy trình nghiệp vụ](chat_workflow.md)
