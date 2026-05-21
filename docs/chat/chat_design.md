# Chat Service - Thiết kế Kiến trúc

**Liên kết nhanh (Related Documents):**
* [Cấu trúc Database](chat_db_schema.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)
* [Quy trình nghiệp vụ (Workflows)](chat_workflow.md)

---

## 1. Tổng quan kiến trúc

Hệ thống gồm các thành phần chính:

### 1.1 Các thành phần

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS                                             │
│                         Mobile / Web Application                                        │
│                    REST API │ WebSocket │ HTTP Upload                                   │
└───────────────────────────────────┬───────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              SERVICE LAYER                                             │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  ┌───────────────────────┐     ┌───────────────────────┐     ┌─────────────────────┐  │
│  │   Chat API Service     │     │   WebSocket Server    │     │   Other Services   │  │
│  │                       │     │      Cluster          │     │                    │  │
│  │  - REST APIs          │     │                       │     │  - User Service   │  │
│  │  - Send message       │     │  - Persistent conn    │     │  - Order Service  │  │
│  │  - Query history      │     │  - Subscribe rooms    │     │  - Product Service│  │
│  │  - Validate           │     │  - Broadcast          │     │                    │  │
│  │  - Idempotency check │     │  - Presence update    │     │                    │  │
│  └──────────┬────────────┘     └──────────┬────────────┘     └─────────────────────┘  │
│             │                               │                                           │
│             │                               │                                           │
└─────────────┼───────────────────────────────┼───────────────────────────────────────────┘
              │                               │
              ▼                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE BROKER                                             │
│                                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐    │
│  │                         APACHE KAFKA                                            │    │
│  │                                                                                 │    │
│  │  Topic: chat.messages                                                            │    │
│  │  - Partitions: 12 (có thể tăng)                                                │    │
│  │  - Partition Key: conversation_id                                               │    │
│  │  - Retention: 7 ngày                                                            │    │
│  │  - acks: all                                                                    │    │
│  │                                                                                 │    │
│  └─────────────────────────────────────────────────────────────────────────────────┘    │
│                    ▲                               │                                    │
│                    │                               │                                    │
└────────────────────┼───────────────────────────────┼────────────────────────────────────┘
                     │                               │
                     │            ┌──────────────────┬┴──────────────────┐
                     │            │                  │                   │
                     │            ▼                  ▼                   │
                     │     ┌──────────────┐  ┌──────────────┐         │
                     │     │  WebSocket   │  │   Message     │         │
                     │     │  Gateway     │  │  Persister    │         │
                     │     │  (Consumer)  │  │  (Consumer)   │         │
                     │     └──────────────┘  └──────────────┘         │
                     │            │                  │                   │
                     │            ▼                  ▼                   │
                     │     ┌──────────────┐  ┌──────────────┐         │
                     │     │   Clients   │  │  ScyllaDB    │         │
                     │     │ (WebSocket)│  │ (Cassandra) │         │
                     │     └──────────────┘  └──────────────┘         │
                     │                                                      │
                     │  Chat Service produce ───────────────────────────────┘
                     │  event (chat.message.received)                        │
                     │                                                      │ Notification
                     │                                                      │ Service
                     │                                                      │ (Subscriber)
                     ▼                                                      ▼
              ┌─────────────────────────────────────────────────────────────────────────┐
              │                     KAFKA: chat.message.received                       │
              │                     (Notification Service subscribe)                     │
              └─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Mô tả các thành phần

| Component | Mô tả |
|-----------|--------|
| **Client (Mobile/Web)** | Kết nối WebSocket để nhận real-time, gửi tin nhắn qua REST hoặc qua chính WebSocket |
| **Chat API Service** | Nhận yêu cầu gửi tin nhắn, validate, kiểm tra idempotency, sinh ID tin nhắn, đẩy vào Kafka |
| **WebSocket Server Cluster** | Mỗi server mở nhiều kết nối WebSocket từ client, đồng thời là một Kafka consumer group riêng biệt để nhận toàn bộ tin nhắn mới |
| **Apache Kafka** | Lưu trữ tạm thời và phân phối message. Topic chính `chat.messages` |
| **Message Persister** | Kafka Consumer Group riêng, đọc từ chat.messages và ghi vào Cassandra |
| **Event Publisher** | Chat Service produce event `CHAT_MESSAGE_RECEIVED` vào topic riêng để Notification Service xử lý notification |
| **Notification Service** | Subscribe topic `chat.message.received`, gửi push/in-app notification qua kênh thống nhất của hệ thống |
| **Presence Service** | Dùng Redis để theo dõi trạng thái online/offline của user |
| **Redis** | Dùng cho idempotency cache, presence, và hot cache (optional) |
| **ScyllaDB** | Lưu trữ lịch sử tin nhắn (chính) |
| **S3** | Lưu file đính kèm |

---

## 2. Lựa chọn Message Broker: Kafka

### 2.1 So sánh Redis Pub/Sub vs Kafka

| Tiêu chí | Redis Pub/Sub | Kafka |
|-----------|---------------|-------|
| **Độ bền vững** | Không lưu message, fire-and-forget. Nếu consumer ngắt, mất message. | Lưu message trên đĩa, có thể replay, giữ lại theo thời gian (vd 7 ngày). |
| **Thứ tự** | Không đảm bảo thứ tự khi publish liên tục. | Đảm bảo thứ tự trong cùng partition - quan trọng cho chat. |
| **Broadcast** | Tự nhiên: nhiều subscriber nhận cùng message. | Cần tạo consumer group riêng cho từng WebSocket server. |
| **Khả năng tích hợp** | Chỉ là pub/sub, cần tự xây pipeline lưu trữ. | Dùng chung topic cho cả broadcast lẫn lưu trữ, notification. |
| **Chi phí vận hành** | Nhẹ, dễ cài. | Nặng hơn, cần quản lý cluster, nhưng mạnh mẽ hơn về lâu dài. |

### 2.2 Quyết định

**Dùng Kafka làm trục chính.**

Để broadcast real-time, mỗi WebSocket server là một consumer group riêng (như đã giải thích kỹ).

**Tối ưu thêm (optional):** Có thể đặt một lớp Redis Pub/Sub phía trước (Chat API publish vào Redis, các WebSocket server subscribe Redis) và vẫn đẩy vào Kafka riêng để lưu trữ, nhưng sẽ tăng độ phức tạp. Ở đây chọn giải pháp thuần Kafka cho rõ ràng.

### 2.3 Cấu hình Topic

```
Topic: chat.messages
- Số partition: 12 (có thể tăng sau, nhưng phải cân nhắc vì tăng partition làm mất thứ tự nếu không cẩn thận khi re-partition)
- Mỗi partition xử lý một tập các conversation_id
- Producer gửi message với key = conversation_id
- Retention: 7 ngày (đủ cho replay khi cần, đồng thời không quá tốn đĩa)

Topic: chat.message.received
- Notification Service subscribe để gửi push/in-app notification
```

---

## 3. Quản lý kết nối WebSocket

### 3.1 Cấu trúc dữ liệu trong memory

Mỗi WebSocket server duy trì hai map:

```
roomSockets: map[conversationId]map[*Connection]bool
userSockets: map[userId]map[*Connection]bool  // để biết user online tổng thể
```

### 3.2 Khi client kết nối

1. Xác thực JWT trong URL param hoặc message đầu tiên.
2. Tạo Connection struct, lưu userId.
3. Client gửi message "subscribe" kèm danh sách conversationId cần theo dõi.
4. Server thêm connection vào các map tương ứng.

### 3.3 Khi client gửi tin nhắn qua WebSocket

Client gửi frame:
```json
{"type": "send", "conv_id": "...", "idempotency_key": "...", "content": "..."}
```

WebSocket server gọi thẳng vào Chat API nội bộ để tập trung idempotency.

### 3.4 Khi client ngắt kết nối

1. Xóa connection khỏi roomSockets và userSockets.
2. Nếu không còn connection nào cho userId, thông báo Presence Service: `DEL user:presence:userId`
3. Publish một event "user:offline" vào Redis để các server khác cập nhật UI.

---

## 4. Presence Service

### 4.1 Cơ chế hoạt động

Dùng Redis với SET key + TTL:

```
Khi user online: SET user:presence:{userId} "online" EX 60
Khi heartbeat:  SET user:presence:{userId} "online" EX 60
Khi offline:     DEL user:presence:{userId}
```

### 4.2 Kiểm tra trạng thái

```
EXISTS user:presence:{userId}
→ EXISTS = 1: online
→ EXISTS = 0: offline
```

### 4.3 Thông tin thiết bị (optional)

Có thể lưu thêm thông tin thiết bị cho multi-device support và push notification targeting.

---

## 5. Xử lý Idempotency

### 5.1 Tại phía Chat API (Producer)

1. Client gửi kèm `idempotency_key` (UUID v4)
2. Chat API kiểm tra Redis: `GET idem:{convId}:{senderId}:{idempotency_key}`
3. Nếu tồn tại: trả về message_id đã lưu
4. Nếu chưa tồn tại: `SET idem:{convId}:{senderId}:{idempotency_key} <message_id> NX EX 86400`
5. Nếu SET thành công: produce Kafka
6. Nếu SET thất bại (key đã có): retry get và return

### 5.2 Tại phía Client (nhận tin nhắn)

1. Client duy trì LRU cache các message_id đã hiển thị
2. Khi nhận message qua WebSocket: kiểm tra dedup cục bộ
3. Khi reconnect: gửi `last_message_id` lên server để sync

### 5.3 Tại phía WebSocket Server

1. Không cần dedup (chỉ forward)
2. Dùng auto-commit với interval nhỏ (1 giây)
3. Chấp nhận at-least-once, client tự dedup

---

## 6. Đảm bảo thứ tự tin nhắn

### 6.1 Cơ chế của Kafka

- Dùng `conversation_id` làm partition key
- Mọi message trong cùng conversation vào cùng một partition
- Kafka đảm bảo thứ tự trong partition

### 6.2 Consumer

Consumer đọc từ partition theo đúng offset tăng dần.

### 6.3 Reconnect

- Tin nhắn lịch sử query từ Cassandra
- Dùng message_id (Snowflake/ULID) để sắp xếp
- Client tự sắp xếp theo `created_at` hoặc `message_id`

---

## 7. Sơ đồ luồng

```
Client A                    Client B
  |                            |
  | WebSocket                  | WebSocket
  v                            v
WebSocket Server 1         WebSocket Server 2
  |                            |
  | (Consumer Group: ws-1)     | (Consumer Group: ws-2)
  |                            |
  +-------> Kafka Topic <------+--------------+
              chat.messages                    |
                  ^                            |
                  |                            v
           Chat API Service              Persister
           (Producer)                    (Cassandra)
           (REST/WS)
                  │
                  │ (Produce)
                  ▼
         chat.message.received
                  │
                  ▼
         Notification Service
         (In-app + Push)
```

### Luồng chi tiết:

1. Client A gửi tin nhắn -> Chat API (qua REST)
2. Chat API kiểm tra idempotency Redis -> produce Kafka
3. Kafka partition (key=conv123) lưu message
4. WS Server 1 và WS Server 2 (các consumer group riêng) đều kéo message
5. WS Server 1 có Client A, không cần gửi lại cho A vì A đã thấy optimistic; WS Server 2 có Client B -> gửi cho B
6. Persister ghi vào Cassandra
7. Chat Service produce event `CHAT_MESSAGE_RECEIVED` vào topic `chat.message.received`
8. Notification Service subscribe topic này, kiểm tra presence, gửi push/in-app notification nếu cần

---

## 8. Đường dẫn tài liệu liên quan

- [Cấu trúc Database](chat_db_schema.md) - Schema ScyllaDB và Redis
- [Cấu trúc Sự kiện](chat_events.md) - Kafka topics và event contracts
- [Quy trình nghiệp vụ](chat_workflow.md) - Chi tiết các workflow
