# Chat Service - ScyllaDB Schema (V3)

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)
* [Quy trình nghiệp vụ (Workflows)](chat_workflow.md)

---

## 1. Nguyên tắc mô hình hóa (Modeling)

1. Ưu tiên truy vấn (Query-first), mỗi đường dẫn đọc (read path) có bảng riêng.
2. Tránh phân vùng lớn (large partition) bằng cách gom nhóm thời gian (time bucket).
3. Không dùng counter làm source of truth cho số tin nhắn chưa đọc (unread).
4. At-least-once bắt buộc có outbox + tính lặp lại của người tiêu dùng (consumer idempotency).
5. Ưu tiên đọc cho Inbox (Read-first inbox): chấp nhận việc ghi phức tạp hơn để giữ trải nghiệm người dùng (UX) ổn định.

## 2. Phòng chat (Room) và Phân chiếu người tham gia (Participant projection)

### 2.1. rooms_by_id (Bảng chính lưu trữ thông tin phòng chat)

```sql
CREATE TABLE chat.rooms_by_id (
  room_id uuid PRIMARY KEY,
  conversation_type text,
  platform_id uuid,
  customer_id uuid,
  policy_profile_id text,
  retention_class text,
  status text,
  created_at timestamp,
  updated_at timestamp
);
```

### 2.2. room_participants (Người tham gia phòng chat)

```sql
CREATE TABLE chat.room_participants (
  room_id uuid,
  participant_id uuid,
  participant_role text,
  joined_at timestamp,
  left_at timestamp,
  PRIMARY KEY ((room_id), participant_id)
);
```


## 3. Dòng thời gian tin nhắn (Messages timeline)

### 3.1. messages_by_room_bucket (Tin nhắn theo nhóm thời gian)

```sql
CREATE TABLE chat.messages_by_room_bucket (
  room_id uuid,
  bucket_ym int,
  created_at timeuuid,
  message_id uuid,
  message_seq bigint,
  sender_role text,
  sender_id uuid,
  sender_name text,
  msg_type text,
  content text,
  metadata_json text,
  metadata_visibility text,
  is_revoked boolean,
  revoked_at timestamp,
  edited_at timestamp,
  PRIMARY KEY ((room_id, bucket_ym), created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC, message_id DESC);
```

### 3.2. message_by_id (Tra cứu tin nhắn theo ID)

```sql
CREATE TABLE chat.message_by_id (
  message_id uuid PRIMARY KEY,
  room_id uuid,
  bucket_ym int,
  created_at timeuuid,
  message_seq bigint,
  sender_role text,
  sender_id uuid,
  msg_type text,
  is_revoked boolean
);
```

### 3.3. ingress_idempotency_by_sender (Kiểm soát lặp lại tin nhắn gửi đến)

```sql
CREATE TABLE chat.ingress_idempotency_by_sender (
  sender_id uuid,
  client_request_id text,
  message_id uuid,
  created_at timestamp,
  PRIMARY KEY ((sender_id), client_request_id)
);
```

## 4. Trạng thái Inbox và Chỉ mục đã sắp xếp

### 4.1. room_state_by_participant (Trạng thái phòng chat của mỗi người dùng)

```sql
CREATE TABLE chat.room_state_by_participant (
  participant_id uuid,
  room_id uuid,
  conversation_type text,
  partner_id uuid,
  last_message_id uuid,
  last_message_preview text,
  last_message_at timestamp,
  last_sender_role text,
  last_message_seq bigint,
  last_read_seq bigint,
  is_pinned boolean,
  is_muted boolean,
  updated_at timestamp,
  PRIMARY KEY ((participant_id), room_id)
);
```

### 4.2. inbox_index_by_participant_time (Chỉ mục Inbox theo thời gian)

```sql
CREATE TABLE chat.inbox_index_by_participant_time (
  participant_id uuid,
  bucket_ym int,
  last_message_at timestamp,
  room_id uuid,
  PRIMARY KEY ((participant_id, bucket_ym), last_message_at, room_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC, room_id ASC);
```


## 5. Watermarks và bảo mật biên nhận đã đọc

### 5.1. room_watermarks (Đánh dấu trạng thái tin nhắn trong phòng)

```sql
CREATE TABLE chat.room_watermarks (
  room_id uuid,
  participant_id uuid,
  last_delivered_seq bigint,
  last_read_seq bigint,
  last_delivered_at timeuuid,
  last_read_at timeuuid,
  PRIMARY KEY ((room_id), participant_id)
);
```



## 7. Thư viện phương tiện (Media gallery)

```sql
CREATE TABLE chat.media_gallery_by_room (
  room_id uuid,
  media_type text,
  bucket_ym int,
  created_at timeuuid,
  message_id uuid,
  content_url text,
  sender_id uuid,
  width int,
  height int,
  duration_ms int,
  file_size_bytes bigint,
  PRIMARY KEY ((room_id, media_type, bucket_ym), created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC, message_id DESC);
```


## 9. Bộ cấp phát số thứ tự (Sequence allocator)

```sql
CREATE TABLE chat.room_message_sequence (
  room_id uuid PRIMARY KEY,
  last_seq bigint,
  updated_at timestamp
);
```

## 10. Bảng đảm bảo độ tin cậy của sự kiện (Event reliability)

### 10.1. chat_outbox_events (Hàng đợi sự kiện gửi đi)

```sql
CREATE TABLE chat.chat_outbox_events (
  event_id uuid PRIMARY KEY,
  aggregate_type text,
  aggregate_id uuid,
  event_type text,
  event_schema_version int,
  partition_key text,
  payload_json text,
  status text,
  retry_count int,
  max_retries int,
  next_retry_at timestamp,
  error_message text,
  created_at timestamp,
  published_at timestamp
);
```

### 10.2. chat_consumer_idempotency (Kiểm soát lặp lại ở phía nhận sự kiện)

```sql
CREATE TABLE chat.chat_consumer_idempotency (
  consumer_group text,
  event_id uuid,
  processed_at timestamp,
  PRIMARY KEY ((consumer_group), event_id)
);
```

