# User Service - Trình Tự Nghiệp Vụ (Workflows)

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](user_workflows.md)
*   [Caching Schema (Redis)](user_caching_schema.md)
*   [Database Schema](user_db_design.md)
*   [Events Structure & Kafka Topics](user_events.md)

---
Tài liệu này mô tả 9 luồng công việc (workflows) cốt lõi của User Service nhằm xử lý hồ sơ nhân khẩu học, khởi tạo dữ liệu sau đăng ký, quản lý sổ địa chỉ (với cache cho checkout), cài đặt cá nhân và cung cấp dữ liệu cho các hệ sinh thái khác (Order, Chat, Notification).

---

## 1. Luồng Khởi Tạo Hồ Sơ (Event-Driven Profile Creation)

Như đã phân tích, hệ thống tách biệt Auth Service và User Service. Khi Auth xử lý thành công bước đăng ký, Auth đẩy thông điệp sang Kafka và User Service sẽ dùng Worker để tạo vỏ hồ sơ.

```mermaid
sequenceDiagram
    participant App as Client
    participant Auth as Auth Service
    participant Kafka as Kafka (t.auth.events)
    participant UserWorker as User Svc (Kafka Consumer)
    participant UserDB as User Postgres DB

    App->>Auth: POST /register { email, password }
    Auth->>Auth: Lưu DB, trạng thái PENDING_VERIFICATION
    App->>Auth: POST /verify-email { otp }
    Auth->>Auth: Đổi trạng thái ACTIVE, sinh event
    Auth->>Kafka: Publish `USER_VERIFIED_SUCCESS` { id, email }

    Kafka-->>UserWorker: Consume `USER_VERIFIED_SUCCESS`
    UserWorker->>UserWorker: Kiểm tra tính lũy đẳng: Cài đặt PostgreSQL với `ON CONFLICT (id) DO NOTHING`
    UserWorker->>UserDB: INSERT `user_profiles` (id = payload.user_id)
    UserWorker->>UserDB: INSERT `user_preferences` mặc định (user_id = payload.user_id) `ON CONFLICT (user_id) DO NOTHING`
```

**Nguyên tắc xử lý:**
* Luồng này đảm bảo độ phản hồi API `/verify-email` phía Auth cực nhanh vì không phải đợi User Service Insert.
* Nếu User Service bị down (sập), thư Kafka `USER_VERIFIED_SUCCESS` vẫn nằm chờ. Khi User Service hồi sinh (restart), nó đọc tiếp thông điệp và bổ khuyết Data.
* Tính lũy đẳng (Idempotency) được đảm bảo qua `ON CONFLICT DO NOTHING` - đảm bảo worker chạy lại nhiều lần vẫn không tạo duplicate.

---

## 2. Luồng Quản Lý Sổ Địa Chỉ - CRUD Operations (Address Management)

Order Service yêu cầu dữ liệu Snapshot, nên User Service áp dụng **Hard Delete** (xóa thẳng từ CSDL) nhằm nhẹ gánh cho Database. Danh sách địa chỉ được cache với Redis để tối ưu hiệu năng cho luồng Checkout.

### 2.1. Luồng Thêm Địa Chỉ Mới (Create Address)

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Redis as Redis L2 Cache

    App->>API: POST /api/v1/users/me/addresses { address data }
    API->>API: Validate address fields (ward_id, district_id, city_id)
    API->>DB: INSERT `user_addresses`
    alt First address for user
        API->>DB: UPDATE is_default = TRUE
    end
    API->>DB: Commit Transaction
    API->>Redis: DEL user:caches:addresses:{user_id}
    API-->>App: 201 Created { address_id }
```

**Nguyên tắc xử lý:**
* Nếu đây là địa chỉ đầu tiên của user, tự động set `is_default = TRUE`.
* Sau khi insert thành công, xóa cache Redis để đảm bảo consistency.

### 2.2. Luồng Cập Nhật Địa Chỉ (Update Address)

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Redis as Redis L2 Cache

    App->>API: PUT /api/v1/users/me/addresses/{address_id} { updated data }
    API->>API: Verify address belongs to current user (authorization check)
    API->>DB: UPDATE `user_addresses` SET ... WHERE id = {address_id}
    API->>DB: Commit Transaction
    API->>Redis: DEL user:caches:addresses:{user_id}
    API-->>App: 200 OK
```

**Lưu ý bảo mật:**
* Kiểm tra `user_id` sở hữu address trước khi update để tránh hack sang address người khác.

### 2.3. Luồng Set Default (Address Rotation)

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Redis as Redis L2 Cache

    App->>API: POST /api/v1/users/me/addresses/{address_id}/default
    API->>DB: BEGIN Transaction
    API->>DB: UPDATE user_addresses SET is_default = FALSE WHERE user_id = {user_id} AND is_default = TRUE
    API->>DB: UPDATE user_addresses SET is_default = TRUE WHERE id = {address_id} AND user_id = {user_id}
    API->>DB: COMMIT Transaction
    API->>Redis: DEL user:caches:addresses:{user_id}
    API-->>App: 200 OK
```

**Nguyên tắc xử lý:**
* Sử dụng Database Transaction để đảm bảo atomicity - không thể có 2 địa chỉ default cùng lúc.
* Partial Unique Index `idx_unique_default_address` trên `user_addresses` đảm bảo ràng buộc ở cấp database.

### 2.4. Luồng Xóa Địa Chỉ - Hard Delete

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Redis as Redis L2 Cache

    App->>API: DELETE /api/v1/users/me/addresses/{address_id}
    API->>API: Verify address belongs to current user
    API->>DB: BEGIN Transaction

    alt Address is default and no other addresses
        API->>DB: ROLLBACK (Keep transaction open for proper error)
        API-->>App: 409 Conflict "Không thể xóa địa chỉ mặc định duy nhất"
    else Address is default and has other addresses
        API->>DB: SELECT MIN(id) FROM user_addresses WHERE user_id = {user_id} AND id != {address_id}
        API->>DB: UPDATE user_addresses SET is_default = TRUE WHERE id = (SELECT MIN(id)...)
    end

    API->>DB: DELETE FROM user_addresses WHERE id = {address_id}
    API->>DB: COMMIT Transaction
    API->>Redis: DEL user:caches:addresses:{user_id}
    API-->>App: 204 No Content
```

**Quy tắc nghiệp vụ:**
* Không cho phép xóa địa chỉ mặc định duy nhất.
* Nếu xóa địa chỉ default và còn địa chỉ khác, tự động promote địa chỉ khác thành default.
* Áp dụng Hard Delete để nhẹ gánh Database (Order Service đã snapshot vào bảng `orders`).

### 2.5. Luồng Đọc Danh Sách Địa Chỉ với L2 Cache (Read Addresses for Checkout)

Danh sách địa chỉ là dữ liệu được truy cập thường xuyên (Checkout page), nên được cache với chiến lược Cache-Aside.

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant Redis as Redis L2 Cache
    participant DB as Postgres

    App->>API: GET /api/v1/users/me/addresses
    API->>Redis: GET user:caches:addresses:{user_id}
    alt Cache Hit
        Redis-->>API: Return cached address list
        API-->>App: 200 OK (from cache)
    else Cache Miss
        API->>DB: SELECT * FROM user_addresses WHERE user_id = {user_id} ORDER BY is_default DESC, created_at DESC
        alt Has addresses
            API->>Redis: SETEX user:caches:addresses:{user_id} 43200 {json_array}
        else No addresses
            API->>Redis: SETEX user:caches:addresses:{user_id} 1800 [] (short TTL for empty cache)
        end
        API-->>App: 200 OK
    end
```

**Cache Key Pattern:**
* `user:caches:addresses:{user_id}` với TTL 12 giờ (43200 seconds)

**Cache Invalidation Events:**
* Tạo mới address -> `DEL user:caches:addresses:{user_id}`
* Cập nhật address -> `DEL user:caches:addresses:{user_id}`
* Xóa address -> `DEL user:caches:addresses:{user_id}`
* Set default -> `DEL user:caches:addresses:{user_id}`

**Empty Cache Policy:**
* Nếu user chưa có địa chỉ, cache empty array `[]` với TTL ngắn (30 phút) để tránh cache penetration.

---

## 3. Luồng Cập Nhật Hồ Sơ Của User (Update Profile)

Chức năng then chốt của Hệ thống để đồng bộ hiển thị và giải quyết triệt để việc cập nhật dữ liệu hồ sơ người dùng.

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Kafka as Event Topic (t.user.events)

    App->>API: PUT /api/v1/users/me/profile { phone, fullname... }
    API->>API: Ràng buộc Validation (Check SĐT, Validate Unicode..)
    API->>DB: UPDATE `user_profiles`
    alt Success
        API->>DB: Commit Transaction
        API->>Kafka: Publish `USER_PROFILE_UPDATED` directly
    else Failure
        API->>DB: Rollback
        API-->>App: Error response
    end
    API-->>App: OK 200 HTTP
```

**Lưu ý xử lý nghiệp vụ:**
* Nếu `phone_number` trùng với user khác (vi phạm unique), API trả lỗi 409 Conflict với thông điệp nghiệp vụ rõ ràng.
* Sử dụng direct Kafka publishing: sau khi DB UPDATE thành công, UserEventProducer publish trực tiếp lên Kafka topic `t.user.events`.
* Nếu publish thất bại, DB không rollback (eventual consistency qua retry/circuit breaker).

---

## 4. Luồng Quản Lý Preferences (Update Preferences)

User Service quản lý các thiết lập ứng dụng của người dùng. Dữ liệu này được đồng bộ sang Notification Service qua Kafka events.

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres
    participant Kafka as Event Topic (t.user.events)

    App->>API: PUT /api/v1/users/me/preferences { language, currency, notification flags }
    API->>API: Validate preferences (valid language code, valid currency)
    API->>DB: UPDATE `user_preferences`
    alt Success
        API->>DB: Commit Transaction
        API->>Kafka: Publish `USER_PREFERENCES_UPDATED` directly
    else Failure
        API->>DB: Rollback
        API-->>App: Error response
    end
    API-->>App: 200 OK
```

**Các trường Preferences:**
| Field | Giá trị mặc định | Mô tả |
|:------|:-----------------|:-------|
| `language` | `'vi'` | Ngôn ngữ hiển thị |
| `currency` | `'VND'` | Tiền tệ ưu tiên |
| `email_enabled` | `TRUE` | Bật thông báo qua Email |
| `in_app_enabled` | `TRUE` | Bật thông báo In-App |
| `marketing_enabled` | `TRUE` | Bật tin quảng cáo/khuyến mãi |

**Lưu ý:**
* Preferences được tạo mặc định khi User Service nhận `USER_VERIFIED_SUCCESS` (Workflow 1).
* Notification Service tiêu thụ `USER_PREFERENCES_UPDATED` để sync preferences cho việc gửi thông báo.

---

## 5. Luồng Cung Cấp Dữ Liệu Cho Chat Service (Event-Driven Projection)

Chat Service duy trì bản sao thông tin user (Name) trong DB nội bộ thông qua Event-Driven Projection.

```mermaid
sequenceDiagram
    participant UserApp as Client App
    participant UserSvc as User Service
    participant Kafka as Kafka (t.user.events)
    participant ChatWorker as Chat Kafka Consumer
    participant ChatDB as Chat ScyllaDB
    participant ChatAPI as Chat Service API

    UserApp->>UserSvc: PUT /profile { first_name, last_name }
    UserSvc->>UserSvc: UPDATE user_profiles
    UserSvc->>Kafka: Publish USER_PROFILE_UPDATED directly
    Kafka-->>ChatWorker: Consume USER_PROFILE_UPDATED
    ChatWorker->>ChatDB: UPDATE room_participants SET sender_name
    ChatWorker->>ChatDB: UPDATE messages_by_room_bucket SET sender_name

    UserApp->>ChatAPI: Load Room (participant_id = uuid)
    ChatAPI->>ChatDB: SELECT sender_name FROM room_participants
    ChatDB-->>ChatAPI: Return participant info
    ChatAPI-->>UserApp: Return room data with display names
```

**Lưu ý kiến trúc:**
- Chat Service **KHÔNG** gọi trực tiếp User Database.
- Tất cả dữ liệu user được đồng bộ qua Kafka events.
- Nếu Chat Worker chưa xử lý kịp, Chat Service có thể fallback gọi User Service qua gRPC để lấy thông tin (tùy chọn).

---

## 6. Luồng Xóa Tài Khoản (GDPR 2-Giai Đoạn)

Khi Auth Service phát sự kiện `USER_DELETED`, User Service thực hiện xóa theo 2 giai đoạn để tuân thủ GDPR:

| Giai đoạn | Thời điểm | Mô tả |
|:----------|:-----------|:-------|
| **Giai đoạn 1: Anonymize** | Ngay khi nhận event | Anonymize PII, publish `ACCOUNT_DELETED` cho downstream |
| **Giai đoạn 2: Hard Delete** | Sau 90 ngày (`retention_window_expires_at`) | Compliance Worker xóa vĩnh viễn dữ liệu |

> **Tại sao cần 2 giai đoạn?**
> Kafka không phải permanent storage (mặc định retention 7 ngày). Bảng `user_deletions` đóng vai trò Event Store để Compliance Worker có thể truy vấn và xóa vĩnh viễn sau 90 ngày.

### 6.1. Giai đoạn 1: Anonymize (Ngay lập tức)

```mermaid
sequenceDiagram
    participant Auth as Auth Service
    participant Kafka as Kafka (t.auth.events)
    participant UserWorker as User Svc (Kafka Consumer)
    participant DB as Postgres
    participant KafkaOut as Kafka (t.user.events)

    Auth->>Kafka: Publish `USER_DELETED` { user_id, reason, retention_window_expires_at }
    Kafka-->>UserWorker: Consume `USER_DELETED`
    UserWorker->>DB: BEGIN Transaction
    UserWorker->>DB: INSERT user_deletions { status: 'ANONYMIZED' }
    UserWorker->>DB: UPDATE user_profiles SET (first_name='Deleted', last_name='User', phone=null...)
    UserWorker->>DB: DELETE FROM user_addresses WHERE user_id = {user_id}
    UserWorker->>DB: DELETE FROM user_preferences WHERE user_id = {user_id}
    UserWorker->>DB: COMMIT Transaction
    UserWorker->>KafkaOut: Publish ACCOUNT_DELETED directly
```

**Thứ tự xử lý:**
1. INSERT vào `user_deletions` với `status = 'ANONYMIZED'`
2. UPDATE `user_profiles`: anonymize PII (tên → "Deleted User", SĐT → null)
3. DELETE `user_addresses` (Order Service đã snapshot vào bảng `orders`)
4. DELETE `user_preferences`
5. COMMIT transaction
6. Publish `ACCOUNT_DELETED` trực tiếp lên Kafka qua UserEventProducer

### 6.2. Giai đoạn 2: Hard Delete (Sau 90 ngày)

```mermaid
sequenceDiagram
    participant Scheduler as Cron Scheduler
    participant Worker as Compliance Worker
    participant DB as Postgres

    Scheduler->>Worker: Trigger Daily (e.g., 02:00 AM)
    Worker->>DB: SELECT * FROM user_deletions<br/>WHERE retention_window_expires_at <= NOW()<br/>AND status = 'ANONYMIZED'
    DB-->>Worker: List of records to hard delete

    loop For each record
        Worker->>DB: BEGIN Transaction
        Worker->>DB: DELETE FROM user_profiles WHERE id = {user_id}
        Worker->>DB: DELETE FROM user_deletions WHERE id = {id}
        Worker->>DB: COMMIT Transaction
    end
```

**Hành động của Compliance Worker:**
1. Chạy định kỳ (recommend: daily lúc 02:00 AM)
2. Query các bản ghi có `retention_window_expires_at <= NOW()`
3. HARD DELETE `user_profiles` (bảng cha, sau khi đã anonymize thì không còn PII)
4. DELETE bản ghi trong `user_deletions`

**Lưu ý:**
- `user_addresses` và `user_preferences` đã bị DELETE ở Giai đoạn 1 (không cần xóa lại)
- Hard Delete chỉ cần xóa `user_profiles` vì FK relations đã được cascade

---


---

## 7. Luồng Đọc Hồ Sơ Trực Tiếp (Read Profile - No Cache)

Hồ sơ user được đọc trực tiếp từ Database mà không qua cache vì:
* Dữ liệu profile ít thay đổi nhưng tần suất đọc không cao như address (checkout)
* Việc cache profile không mang lại lợi ích đáng kể về hiệu năng

```mermaid
sequenceDiagram
    participant App as Client
    participant API as User API
    participant DB as Postgres

    App->>API: GET /api/v1/users/me/profile
    API->>DB: SELECT * FROM user_profiles WHERE id = {user_id}
    alt Profile exists
        API-->>App: 200 OK { profile data }
    else Profile not found
        API-->>App: 404 Not Found
    end
```

---

## 8. Tổng Kết Luồng Sự Kiện (Events Summary)

### Event Flow Diagram

```mermaid
flowchart LR
    subgraph AuthService[Auth Service]
        A1[USER_VERIFIED_SUCCESS]
        A2[USER_DELETED]
    end

    subgraph UserService[User Service]
        W1[Kafka Consumer]
        W2[API Server]
        DB[(Postgres DB)]
        Redis[(Redis L2)]
    end

    subgraph DownstreamServices[Downstream Services]
        C1[Chat Service]
        C2[Notification Service]
    end

    A1 -->|t.auth.events| W1
    A2 -->|t.auth.events| W1
    A3 -->|t.auth.events| W1

    W1 -->|INSERT| DB
    W2 -->|R/W| DB
    W2 -->|DEL| Redis

    W2 -->|Kafka Producer| C1
    W2 -->|Kafka Producer| C2
```

### Bảng Mapping Events và Workflows

| Event | Source | Target | Workflow |
|:------|:-------|:-------|:---------|
| `USER_VERIFIED_SUCCESS` | Auth Service | User Service | #1 Profile Creation |
| `USER_DELETED` | Auth Service | User Service | #6.1 Account Deletion (Anonymize) |
| `USER_PROFILE_UPDATED` | User Service (Kafka Producer) | Chat Service | #3 Profile Update |
| `USER_PREFERENCES_UPDATED` | User Service (Kafka Producer) | Notification Service | #4 Preferences Update |
| `ACCOUNT_DELETED` | User Service (Kafka Producer) | Review Service, Notification Service | #6.1 Account Deletion (Notify Downstream) |

### Workers Summary

| Worker | Trigger | Frequency | Mục đích |
|:-------|:--------|:-----------|:---------|
| **GDPR Deletion Consumer** | Kafka (`t.auth.events`) | On-demand | Consume `USER_DELETED`, anonymize PII, publish `ACCOUNT_DELETED` |
| **Compliance Worker** | Cron Scheduler | Daily (02:00 AM) | Hard delete records where `retention_window_expires_at` passed |

### Cache Strategy Summary

| Data | Cached? | Key Pattern | TTL | Purpose |
|:-----|:--------|:------------|:----|:--------|
| **Addresses** | ✅ Yes | `user:caches:addresses:{user_id}` | 12h | Checkout optimization |
| **Profile** | ❌ No | - | - | Direct DB read |
| **Preferences** | ❌ No | - | - | Direct DB read |
