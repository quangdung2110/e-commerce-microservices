# User Service - Caching Schema (Redis)

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](user_workflow.md)
* [Background Workers & Jobs](user_workers.md)
* [Caching Schema (Redis)](user_caching_schema.md)
* [Database Schema (Write Store)](user_db_design.md)
* [State Machine & Lifecycle](user_state_machine.md)
* [Events Structure & Kafka Topics](user_events.md)
* [Architecture Recommendations](user_architecture_recommendations.md)

---

Tài liệu này mô tả chi tiết chiến lược cache cho User Service. Mục tiêu chính là giảm độ trễ read path, bảo vệ PostgreSQL và vẫn giữ được tính nhất quán dữ liệu.

## 1. Nguyên tắc thiết kế cache

1. Redis cache-aside là chiến lược mặc định.
2. Soft invalidation (`SET` ghi đè) là ưu tiên; hạn chế `DEL` trên hot key.
3. Mọi key đều dùng prefix `user:` để phân tách namespace rõ ràng.
4. TTL có jitter để tránh expired đồng loạt.
5. Event-driven self-healing luôn tồn tại để sửa lệch cache nếu in-band update bị miss.

## 2. Danh mục key và chi tiết field

## 2.1 Key nhóm profile

### Key `user:profile:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL | 3600s + jitter (0..300s) |
| Tạo/Cập nhật | In-band sau profile write, và out-of-band từ worker |
| Mục đích | Read path cho `GET /users/me` và gRPC profile |

**Payload đề xuất:**

```json
{
  "user_id": "u-10001",
  "display_name": "nguyen_van_a",
  "first_name": "Nguyen",
  "last_name": "Van A",
  "avatar_url": "https://cdn.example.com/u10001.jpg",
  "status": "ACTIVE",
  "kyc_status": "APPROVED",
  "state_version": 17,
  "updated_at": "2026-03-31T09:00:00Z"
}
```

### Key `user:profile:version:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String (số nguyên) |
| TTL | 24h |
| Mục đích | Version fence cho stale-check nhanh ở một số luồng đồng bộ |

## 2.2 Key nhóm địa chỉ

### Key `user:addresses:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | RedisJSON Array |
| TTL | 86400s |
| Mục đích | Trả danh sách địa chỉ cho checkout/profile |

**Phần tử trong mảng gồm:**
- `address_id`, `full_name`, `phone`, `street`, `district_code`, `province_code`.
- `is_default_shipping`, `is_default_billing`, `updated_at`.

### Key `user:address:{address_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | RedisJSON Object |
| TTL | 86400s |
| Mục đích | Point-read địa chỉ theo ID |

## 2.3 Key nhóm preferences và KYC

### Key `user:preferences:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL | 21600s |
| Mục đích | Read nhanh cài đặt user |

### Key `user:kyc:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL | 3600s |
| Mục đích | KYC status read path cho onboarding/sensitive flows |

## 2.4 Key nhóm compliance/deletion

### Key `user:deletion_status:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL | 1800s |
| Mục đích | Trả trạng thái xóa tài khoản theo retention lifecycle |

## 2.5 Key nhóm vận hành

### Key `user:idempotency:{idempotency_key}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL | 5 phút (lease processing), sau commit tăng 24h |
| Mục đích | Chống double-submit |

### Key `user:rate_limit:profile_update:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | Counter |
| TTL | 60s |
| Mục đích | Chống spam profile updates |

### Key `user:rate_limit:kyc_submit:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | Counter |
| TTL | 1 ngày |
| Mục đích | Chống lạm dụng upload hồ sơ KYC |

### Key `user:lock:profile_refresh:{user_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | Distributed lock key |
| TTL | 2s |
| Mục đích | Chặn stampede cross-pod khi cache miss profile |

## 3. Luồng đọc và ghi cache

## 3.1 Read flow (cache-aside)

1. Kiểm tra Redis key.
2. Nếu hit, trả ngay.
3. Nếu miss, dùng singleflight nội bộ.
4. Pod thắng lock query DB, set cache, rồi trả kết quả.
5. Pod còn lại chờ ngắn và đọc lại từ cache.

## 3.2 Write flow (soft invalidation)

1. Ghi DB + outbox trong cùng transaction.
2. In-band ghi đè key quan trọng nếu đủ payload.
3. Worker self-heal tiêu thụ event để cập nhật key liên quan.
4. Chỉ dùng `DEL` khi key không còn hợp lệ tuyệt đối.

## 4. Chống stampede, penetration, stale write

## 4.1 Stampede protection

1. Local singleflight theo `user_id`.
2. Distributed lock (`SETNX user:lock:profile_refresh:{user_id} EX 2`).
3. TTL jitter cho key profile và key hot-read.

## 4.2 Cache penetration protection

1. Với user không tồn tại, cache empty marker TTL ngắn (30-60s).
2. Tránh việc request sai ID liên tục đập DB.

## 4.3 Stale write protection

1. Worker so sánh `state_version` hoặc `updated_at` trước khi overwrite.
2. Có thể dùng Lua CAS để chặn ghi dữ liệu cũ đè dữ liệu mới.

## 5. Kênh Pub/Sub và format thông điệp

1. Kênh đề xuất: `channel:user:l1-evict` (nếu bật local cache).
2. Chỉ publish sau commit transaction.

**Schema thông điệp:**

```json
{
  "type": "USER_PROFILE_UPDATED",
  "user_id": "u-10001",
  "state_version": 17,
  "updated_at": "2026-03-31T09:00:00Z"
}
```

## 6. Bảng tổng hợp key inventory

| Key Pattern | Loại | TTL | Invalidation | Mục đích |
|---|---|---|---|---|
| `user:profile:{user_id}` | String JSON | 1h + jitter | Overwrite in-band + self-heal worker | Profile point-read |
| `user:profile:version:{user_id}` | String | 24h | Overwrite theo profile event | Version fence |
| `user:addresses:{user_id}` | RedisJSON Array | 24h | Overwrite theo address events | Danh sách địa chỉ |
| `user:address:{address_id}` | RedisJSON Object | 24h | Overwrite theo address events | Point-read địa chỉ |
| `user:preferences:{user_id}` | String JSON | 6h | Overwrite theo preferences event | Read preferences |
| `user:kyc:{user_id}` | String JSON | 1h | Overwrite theo KYC events | Read KYC status |
| `user:deletion_status:{user_id}` | String JSON | 30m | Overwrite theo deletion lifecycle events | Compliance status |
| `user:idempotency:{key}` | String JSON | 5m -> 24h | Auto expire | Duplicate guard |

## 7. SLO và giám sát

1. Cache hit ratio profile > 90%.
2. Cache hit ratio addresses > 92%.
3. Redis p99 latency < 10ms.
4. Cảnh báo lock contention khi tăng đột biến.
5. Cảnh báo divergence khi `state_version` cache < DB kéo dài.
