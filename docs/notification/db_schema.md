# Notification Service Database Schema

Sử dụng PostgreSQL cho dữ liệu chính vì tính toàn vẹn (ACID) cho lịch sử và quản lý template, kết hợp Redis cho caching, idempotency, rate limiting và Pub/Sub.

## 1. PostgreSQL Schema

### 1.1 Templates Table
Quản lý các mẫu thông báo đa ngôn ngữ.

```sql
CREATE TABLE notification_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL, -- e.g., 'ORDER_CREATED', 'OTP_LOGIN'
    channel VARCHAR(20) NOT NULL, -- 'EMAIL', 'IN_APP'
    language VARCHAR(10) NOT NULL DEFAULT 'vi', -- 'vi', 'en'
    subject_template VARCHAR(255), -- For Email
    body_template TEXT NOT NULL, -- Format HTML/Markdown with placeholders like {{order_id}}
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(code, channel, language)
);
```

### 1.2 User Preferences Table
Cho phép người dùng tùy chỉnh/chuẩn hóa cấu hình nhận thông báo.

> Đây là projection từ `USER_PREFERENCES_UPDATED` của User Service và chỉ replicate
> ba cờ notification. `language` và `currency` vẫn thuộc User Service (`vi`/`VND`),
> không được thêm vào bảng này.

```sql
CREATE TABLE notification_user_preferences (
    user_id UUID PRIMARY KEY,
    email_enabled BOOLEAN DEFAULT TRUE,
    in_app_enabled BOOLEAN DEFAULT TRUE,
    marketing_enabled BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### 1.3 Notifications Table
Lưu trữ thông báo được cấu trúc cho In-app.
Sử dụng **Table Partitioning** theo khoảng thời gian (tháng) vì bảng này có độ tăng trưởng cực nhanh.

```sql
CREATE TABLE notifications (
    id UUID,
    user_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL, -- 'SYSTEM', 'TRANSACTION', 'PROMOTION'
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    action_url VARCHAR(500), -- Link deeply inside the App
    read_at TIMESTAMP WITH TIME ZONE, -- NULL means unread
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Indexes for querying in-app notifications
CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);
```

### 1.4 Delivery Logs Table
Lưu trữ trạng thái gửi thông báo và đóng vai trò như hard-layer của Idempotency.

```sql
CREATE TABLE notification_delivery_logs (
    event_id UUID PRIMARY KEY, -- Idempotency key from CloudEvents
    user_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL, -- 'PENDING', 'SUCCESS', 'FAILED', 'RETRIED'
    provider_response TEXT, -- Error message or provider message ID
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- For debugging and monitoring
CREATE INDEX idx_delivery_logs_status ON notification_delivery_logs(status);
```

---

## 2. Redis Design Structure

### 2.1 Idempotency Key (Deduplication)
Ngăn chặn process 1 event 2 lần khi Kafka retry.
- **Key Pattern:** `notify:idemp:{event_id}`
- **Value:** `1`
- **TTL:** 24h - 48h
- **Operation:** Dùng command `SET NX EX 86400 notify:idemp:{event_id} 1`. Nếu trả về 0 nghĩa là đã xử lý.

### 2.2 Rate Limiting (Token Bucket)
Chống spam hoặc vượt quá API Provider giới hạn (Ví dụ: 3 OTP / 5 phút).
- **Key Pattern:** `notify:ratelimit:otp:{user_id}`
- **Value:** `{integer_count}`
- **TTL:** 300s (5 phút)
- **Operation:** `INCR`, nếu đếm > 3 thì Reject/Drop message.

### 2.3 User Preferences Cache
Cache lại preferences để tránh query vào PostgreSQL quá nhiều lần (vì mỗi sự kiện gửi đều cần query preferrence).
- **Key Pattern:** `notify:prefs:{user_id}`
- **Value:** JSON string `{"email": true, "in_app": true, "marketing": false}`
- **TTL:** 1h (Invalidate khi user cập nhật trên App).

### 2.4 WebSocket Pub/Sub Channels
Hỗ trợ WebSocket Cluster push real-time message đến đúng User.
- **Channel Pattern:** `notify:pubsub:user:{user_id}`
- **Message Payload:** JSON string chứa nội dung In-app notification.
- Khi Worker xử lý xong, sẽ `PUBLISH notify:pubsub:user:{uid} '{"title": "Đơn hàng", "content": "..."}'`. Các WebSocket/API pods đang duy trì connection sẽ lắng nghe kênh này và Push xuống socket của client tương ứng.
