# Chat Service - Quy trình nghiệp vụ (Workflows)

**Liên kết nhanh (Related Documents):**
* [Thiết kế kiến trúc](chat_design.md)
* [Cấu trúc Database](chat_db_schema.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)

---

## 1. Quy trình gửi tin nhắn (Send Message)

### 1.1 Client gửi tin nhắn

Client có thể gửi qua REST hoặc qua WebSocket (gửi frame JSON).

#### REST Request

```
POST /api/conversations/{convId}/messages
Header: Authorization: Bearer <jwt>
Body: {
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "type": "text",
  "content": "Xin chào",
  "media_url": null,
  "reply_to": null
}
```

#### WebSocket Frame

```json
{
  "type": "send",
  "conv_id": "conv123",
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "content": "Xin chào",
  "msg_type": "text"
}
```

### 1.2 Chat API xử lý

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    SEND MESSAGE WORKFLOW (Chat API)                                     │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  1. XÁC THỰC & VALIDATE                                                               │
│  ─────────────────────────                                                              │
│     ├── Validate JWT token                                                             │
│     ├── Extract user_id từ token                                                      │
│     └── Validate request body (type, content length, etc)                               │
│                                                                                         │
│  2. KIỂM TRA QUYỀN THAM GIA                                                         │
│  ─────────────────────────────                                                         │
│     └── Check user là participant của conversation                                      │
│                                                                                         │
│  3. KIỂM TRA IDEMPOTENCY                                                              │
│  ────────────────────────────                                                          │
│     ┌─────────────────────────────────────────────────────────────────────────┐        │
│     │  GET idem:{convId}:{senderId}:{idempotency_key}                          │        │
│     │                                                                          │        │
│     │  IF tồn tại:                                                           │        │
│     │      → Return 200 với message_id đã lưu (duplicate thành công)          │        │
│     │                                                                          │        │
│     │  IF chưa tồn tại:                                                      │        │
│     │      → SET idem:{convId}:{senderId}:{idempotency_key} <PENDING> NX EX 86400      │
│     │      → Nếu SET thất bại (race), retry GET và return                    │        │
│     └─────────────────────────────────────────────────────────────────────────┘        │
│                                                                                         │
│  4. SINH MESSAGE_ID                                                                   │
│  ───────────────────                                                                    │
│     ├── Sử dụng Snowflake hoặc ULID                                                  │
│     ├── ID sắp xếp được theo thời gian                                              │
│     └── Duy nhất toàn cầu                                                            │
│                                                                                         │
│  5. TẠO MESSAGE OBJECT                                                               │
│  ──────────────────────                                                                │
│     {                                                                         │
│       "message_id": "msg_xxx",                                                       │
│       "conversation_id": "conv123",                                                 │
│       "sender_id": "userA",                                                         │
│       "type": "text",                                                              │
│       "content": "Xin chào",                                                        │
│       "created_at": 1715000000000,                                                  │
│       "idempotency_key": "550e8400-...",                                            │
│       "reply_to": null                                                             │
│     }                                                                         │
│                                                                                         │
│  6. PRODUCE KAFKA                                                                    │
│  ─────────────────                                                                      │
│     ┌─────────────────────────────────────────────────────────────────────────┐        │
│     │  Topic: chat.messages                                                  │        │
│     │  Key: conversation_id (đảm bảo partition)                              │        │
│     │  Value: JSON message object                                           │        │
│     │  acks: all (đảm bảo ghi vào đa số ISR)                               │        │
│     └─────────────────────────────────────────────────────────────────────────┘        │
│                                                                                         │
│  7. UPDATE IDEMPOTENCY CACHE                                                          │
│  ────────────────────────────                                                          │
│     SET idem:{convId}:{senderId}:{idempotency_key} {message_id} EX 86400             │
│                                                                                         │
│  8. TRẢ VỀ RESPONSE                                                                  │
│  ────────────────────                                                                  │
│     201 Created:                                                                     │
│     {                                                                         │
│       "message_id": "msg_xxx",                                                       │
│       "created_at": "2026-05-18T10:00:00Z"                                         │
│     }                                                                         │
│                                                                                         │
│     Client nhận response, hiển thị ngay (optimistic UI)                              │
│     Client sẽ bỏ qua nếu nhận trùng qua WebSocket                                  │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Quy trình Broadcast Real-time (WebSocket Server)

### 2.1 Kiến trúc Consumer

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                 WEBSOCKET SERVER AS KAFKA CONSUMER                                     │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  Mỗi WebSocket Server (instance) là một Kafka Consumer Group riêng:                   │
│  group.id = ws-{hostname} hoặc ws-{instance-id}                                        │
│                                                                                         │
│  Configuration:                                                                         │
│  - auto.offset.reset = latest (chỉ nhận message mới, không lịch sử)                   │
│  - enable.auto.commit = true (auto commit sau 1 giây)                                │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Logic xử lý message từ Kafka

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    BROADCAST WORKFLOW (WebSocket Server)                                │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  KHI NHẬN MESSAGE TỪ KAFKA:                                                           │
│  ─────────────────────────────                                                          │
│                                                                                         │
│  1. PARSE MESSAGE                                                                      │
│     Parse JSON từ Kafka message value                                                 │
│                                                                                         │
│  2. TÌM SUBSCRIBERS                                                                  │
│     Lookup roomSockets[conversation_id]                                                │
│     → Lấy tập hợp các connection đang subscribe phòng đó                              │
│                                                                                         │
│  3. BROADCAST ĐẾN CLIENT                                                               │
│     ┌─────────────────────────────────────────────────────────────────────────┐        │
│     │  for each connection in subscribers:                                     │        │
│     │      if connection.user_id != message.sender_id:  // Không gửi lại cho sender │   │
│     │          send to connection.SendChannel (non-blocking)                    │        │
│     │                                                                          │        │
│     │  Non-blocking: nếu channel đầy, bỏ qua hoặc drop connection              │        │
│     └─────────────────────────────────────────────────────────────────────────┘        │
│                                                                                         │
│  4. CLIENT DEDUP                                                                      │
│     Client duy trì LRU cache các message_id đã nhận                                   │
│     Khi nhận message: kiểm tra đã tồn tại trong cache → bỏ qua                      │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Cấu trúc dữ liệu trong memory

```go
type Connection struct {
    ID       string
    UserID   string
    DeviceID string
    Conn     *websocket.Conn
    SendCh   chan []byte
}

type WebSocketServer struct {
    // Local maps
    roomSockets map[string]map[*Connection]bool  // conversationId -> connections
    userSockets map[string]map[*Connection]bool  // userId -> connections
    
    // Kafka consumer
    consumer *kafka.Consumer
    
    // Redis client
    redis *redis.Client
}
```

---

## 3. Quy trình lưu trữ (Message Persister)

### 3.1 Consumer Group

```
Consumer Group: msg-persister
- Có thể scale thành nhiều instance để tăng throughput
- Chia partition giữa các instance
- auto.offset.reset = earliest (để replay nếu cần)
```

### 3.2 Logic xử lý

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    MESSAGE PERSISTER WORKFLOW                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  KHI NHẬN MESSAGE TỪ KAFKA:                                                           │
│  ─────────────────────────────                                                          │
│                                                                                         │
│  1. PARSE MESSAGE                                                                      │
│     Parse JSON từ Kafka message value                                                 │
│                                                                                         │
│  2. KIỂM TRA IDEMPOTENCY                                                              │
│     Check message_id đã tồn tại trong Cassandra chưa                                 │
│     → Nếu tồn tại: skip (idempotent)                                                 │
│                                                                                         │
│  3. GHI VÀO CASSANDRA                                                                │
│     INSERT INTO messages (                                                             │
│       conversation_id, message_id, sender_id, content, type,                            │
│       created_at, reply_to                                                             │
│     ) VALUES (...)                                                                     │
│                                                                                         │
│  4. CẬP NHẬT INBOX STATE (optional)                                                  │
│     Update inbox_index_by_participant cho các participants                             │
│                                                                                         │
│  TẠI SAO KHÔNG GHI TRỰC TIẾP TỪ CHAT API?                                            │
│  ──────────────────────────────────────────                                            │
│  • Giảm độ trễ cho người gửi: Chat API chỉ cần produce Kafka, không phải chờ DB     │
│  • DB ghi bất đồng bộ                                                                │
│  • Có thể scale persister độc lập                                                    │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Quy trình Push Notification (Notification Service)

### 4.1 Consumer Group

```
Consumer Group: push-sender
- Đọc từ topic chat.messages
- Gửi push notification khi user offline
```

### 4.2 Logic xử lý

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    NOTIFICATION SERVICE WORKFLOW                                       │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  KHI NHẬN MESSAGE TỪ KAFKA:                                                           │
│  ─────────────────────────────                                                          │
│                                                                                         │
│  1. GET PARTICIPANTS                                                                  │
│     Query danh sách participants trong conversation                                    │
│     (có thể cache trong Redis)                                                        │
│                                                                                         │
│  2. VỚI MỖI PARTICIPANT (trừ sender):                                                │
│     ┌─────────────────────────────────────────────────────────────────────────┐        │
│     │                                                                          │        │
│     │  a. CHECK PRESENCE                                                        │        │
│     │     EXISTS user:presence:{participantId}                                  │        │
│     │     → Nếu online: skip (WebSocket sẽ nhận)                               │        │
│     │     → Nếu offline: tiếp tục bước tiếp theo                               │        │
│     │                                                                          │        │
│     │  b. DELAY (optional)                                                      │        │
│     │     Chờ 2-3 giây, kiểm tra lại presence                                   │        │
│     │     (Tránh gửi push khi user đang online nhưng WebSocket chưa kịp báo)   │        │
│     │                                                                          │        │
│     │  c. GET FCM/APNs TOKEN                                                    │        │
│     │     Query device tokens từ database                                       │        │
│     │                                                                          │        │
│     │  d. GỬI PUSH                                                              │        │
│     │     Gửi notification qua FCM (Android) hoặc APNs (iOS)                    │        │
│     │     Payload: sender_name, content_preview, badge_count                    │        │
│     │                                                                          │        │
│     └─────────────────────────────────────────────────────────────────────────┘        │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Quy trình Presence Management

### 5.1 Khi client kết nối

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    CLIENT CONNECTION WORKFLOW                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  1. WEBSOCKET HANDSHAKE                                                                │
│     Client kết nối WebSocket với JWT token                                            │
│                                                                                         │
│  2. XÁC THỰC                                                                            │
│     Validate JWT, extract user_id, device_id                                           │
│                                                                                         │
│  3. TẠO CONNECTION                                                                     │
│     Tạo Connection struct với userId, deviceId                                        │
│                                                                                         │
│  4. SUBSCRIBE                                                                          │
│     Client gửi:                                                                        │
│     {                                                                         │
│       "type": "subscribe",                                                           │
│       "conversations": ["conv1", "conv2", "conv3"]                                  │
│     }                                                                         │
│                                                                                         │
│     Server:                                                                            │
│     - Thêm connection vào roomSockets[convId]                                        │
│     - Thêm connection vào userSockets[userId]                                         │
│                                                                                         │
│  5. UPDATE PRESENCE                                                                   │
│     SET user:presence:{userId} "online" EX 60                                        │
│                                                                                         │
│  6. START HEARTBEAT                                                                     │
│     Client gửi heartbeat mỗi 30 giây:                                               │
│     {                                                                         │
│       "type": "heartbeat"                                                            │
│     }                                                                         │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Khi client ngắt kết nối

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    CLIENT DISCONNECTION WORKFLOW                                       │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  1. PHÁT HIỆN DISCONNECT                                                              │
│     - WebSocket close event                                                           │
│     - Hoặc pong timeout (không nhận được heartbeat)                                   │
│                                                                                         │
│  2. XÓA KHỎI MAPS                                                                    │
│     - Xóa connection khỏi roomSockets[convId] (tất cả rooms)                        │
│     - Xóa connection khỏi userSockets[userId]                                        │
│                                                                                         │
│  3. KIỂM TRA USER CÒN ONLINE KHÔNG                                                    │
│     if len(userSockets[userId]) == 0:                                                 │
│         → Không còn connection nào                                                    │
│                                                                                         │
│  4. CẬP NHẬT PRESENCE                                                                │
│     DEL user:presence:{userId}                                                        │
│                                                                                         │
│  5. BROADCAST OFFLINE STATUS (optional)                                               │
│     Publish "user_offline" event vào Redis channel: presence:{userId}                 │
│     → Các server khác có thể cập nhật UI                                            │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Quy trình Reconnect và Sync

### 6.1 Client reconnect

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    RECONNECT & SYNC WORKFLOW                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  1. CLIENT PHÁT HIỆN DISCONNECT                                                      │
│     - WebSocket close event                                                           │
│     - Network error                                                                   │
│                                                                                         │
│  2. EXPONENTIAL BACKOFF                                                               │
│     Retry: 1s, 2s, 4s, 8s, max 30s                                                 │
│                                                                                         │
│  3. RECONNECT                                                                         │
│     Kết nối WebSocket mới với JWT token                                              │
│                                                                                         │
│  4. GỬI SYNC REQUEST                                                                  │
│     Client gửi:                                                                        │
│     {                                                                         │
│       "type": "sync",                                                               │
│       "last_message_id": "msg_old",                                                 │
│       "conversations": ["conv123", "conv456"]                                       │
│     }                                                                         │
│                                                                                         │
│  5. SERVER XỬ LÝ SYNC                                                                 │
│     ┌─────────────────────────────────────────────────────────────────────────┐        │
│     │  for each conversation in conversations:                                │        │
│     │                                                                          │        │
│     │      // Query messages bị miss                                         │        │
│     │      SELECT * FROM messages                                            │        │
│     │      WHERE conversation_id = ?                                         │        │
│     │        AND message_id > last_message_id                                 │        │
│     │      ORDER BY message_id ASC                                          │        │
│     │      LIMIT 100                                                        │        │
│     │                                                                          │        │
│     │      // Trả về messages tuần tự                                       │        │
│     │      for each message:                                                 │        │
│     │          send to client                                                │        │
│     │                                                                          │        │
│     │  OPTIONAL: Dùng Redis hot cache để giảm tải Cassandra                  │        │
│     └─────────────────────────────────────────────────────────────────────────┘        │
│                                                                                         │
│  6. TIẾP TỤC NHẬN REAL-TIME                                                          │
│     Sau khi sync xong, bắt đầu nhận message mới từ Kafka                           │
│     Client vẫn dedup trong quá trình bắt kịp                                        │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Hot Cache (optional)

```
Để giảm tải Cassandra khi sync:
- Redis lưu last 50 messages của mỗi conversation
- Key: room_hot:{conversation_id}
- Khi sync: ưu tiên đọc từ Redis trước
- Persister cập nhật Redis mỗi khi có message mới
```

---

## 7. Quy trình Rate Limiting

### 7.1 Các loại rate limit

| Loại | Limit | Window |
|------|-------|--------|
| Message send | 60/phút | Sliding window |
| Connection attempts | 10/phút | Sliding window |
| Subscribe requests | 100/phút | Sliding window |

### 7.2 Implementation

```
Redis Sliding Window:
ZADD ratelimit:msg:{userId} {timestamp} {requestId}
ZREMRANGEBYSCORE ratelimit:msg:{userId} 0 {now - 60000}
ZCARD ratelimit:msg:{userId}
IF count > 60 → Return 429 Too Many Requests
```

---

## 8. Đường dẫn API

```
Base path: /api/v1/chat

POST   /rooms/{room_id}/messages          - Gửi tin nhắn
GET    /rooms/{room_id}/messages          - Lấy lịch sử tin nhắn
POST   /rooms/{room_id}/read              - Đánh dấu đã đọc
POST   /presence/heartbeat                - Heartbeat presence
GET    /presence/{user_id}                - Kiểm tra presence
```

---

## 9. Đường dẫn tài liệu

- [Thiết kế kiến trúc](chat_design.md) - Tổng quan kiến trúc Chat Service
- [Cấu trúc Sự kiện](chat_events.md) - Kafka topics và event contracts
- [Cấu trúc Database](chat_db_schema.md) - Schema ScyllaDB và Redis
