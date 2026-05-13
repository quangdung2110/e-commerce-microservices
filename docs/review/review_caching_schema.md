# Review Service - Caching Schema

**Liên kết nhanh (Related Documents):**
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---
Review Service là read-heavy nên caching phải đạt đồng thời 3 mục tiêu:

1. Giảm tải PostgreSQL.
2. Tránh stampede khi có invalidation dồn dập.
3. Giữ độ tươi dữ liệu ổn định cho list và aggregate.

## 1. Cache aggregate ratings

**Keys:**
*   `review:prod:agg:{product_id}`

**Data type:** Redis String (JSON payload).

**TTL:** 24 giờ + deterministic jitter theo công thức:

```text
jitter_seconds = (CRC32-IEEE(product_id) % 300) + 1
actual_ttl = 86400 + jitter_seconds
```

**Update strategy:** soft refresh, không hard delete.
*   Event áp dụng: `REVIEW_CREATED`
*   Worker cập nhật DB trước, commit xong mới ghi đè cache bằng `SET`.
*   Cache miss luôn fallback về DB (source of truth).

## 2. Cache danh sách review (ưu tiên page 1)

**Version key:** `review:prod_ver:{product_id}`.

**Data key pattern:**
*   `review:prod:{product_id}:v:{version}:page:1:sort:{sort_type}`
*   `review:prod:{product_id}:v:{version}:filter:{filter_type}:page:1:sort:{sort_type}`

**TTL:** 1-2 giờ + jitter cùng công thức CRC32.

**Invalidation bằng version bump (`INCR review:prod_ver:{product_id}`) khi có:**
*   `REVIEW_HIDDEN`
*   `ADMIN_REPLY_CREATED`
*   `ACCOUNT_DELETED` (áp dụng cho các product có review của user bị xóa tài khoản)

**Không invalidate page public khi `REVIEW_CREATED`/`REVIEW_UPDATED`/`REVIEW_APPROVED`:**
*   API trả payload review vừa tạo/cập nhật để client hiển thị ngay (RYOW), không phụ thuộc cache page 1.

**Trang 2+ luôn đi DB cursor:**
*   Sort newest: `WHERE id < :last_id_v7`
*   Sort top_helpful: `WHERE (helpful_count, id) < (:last_likes, :last_id_v7)`

## 3. Rate limit cho Like/Unlike

### 3.1. Keys

*   `review:ratelimit:{user_id}:{review_id}`: khóa rate limit chống spam, TTL 10s.
*   Rate limit là **shared across all pods** (dùng Redis shared key).

### 3.2. Cấu trúc DB-First

*   Hủy bỏ hoàn toàn các key `review:likers:*`, `review:{review_id}:delta`, `review:dirty_delta_keys`, `review:processing_deltas`.
*   Danh sách like được lưu trữ và dedup nguyên tử trên PostgreSQL thông qua bảng `review_likes`.
*   Việc giảm bớt các Redis Set (trước đây có TTL lên tới 180 ngày) giúp tiết kiệm cực lớn tài nguyên RAM của hệ thống.

## 4. Đồng bộ helpful_count xuống DB

*(Đã loại bỏ cơ chế worker đồng bộ)*

Việc cập nhật `helpful_count` nay được thực hiện nguyên tử bằng **Database Transaction** ngay khi user gọi API Like/Unlike.
Hệ thống không còn delta sync worker, không cần distributed lock, giải quyết triệt để rủi ro mất đồng bộ và dễ dàng scale số lượng pod mà không gặp race-condition. Lượt like của tài khoản bị xóa sẽ không bị trừ lùi (do không ảnh hưởng trải nghiệm chung).

## 5. Consistency windows

| Luồng | Cache đọc chính | Cửa sổ stale cho phép | Cơ chế làm tươi |
|---|---|---|---|
| Aggregate rating | `review:prod:agg:*`| <= 10s (bình thường), <= 30s khi stale-while-revalidate | Worker overwrite sau commit + async refresh |
| Product review page 1 | `review:prod:*:page:1:*` | <= 60s | Version bump + lazy rebuild |
| Helpful sort | DB + cache page 1 | Không độ trễ | Truy xuất trực tiếp DB hoặc phụ thuộc page 1 |
| Anonymization display | Cache page 1 theo product | <= 60s | Batch product invalidation từ `ACCOUNT_DELETED` |

## 6. Tối ưu hiệu năng bổ sung

1. Prewarm page 1 cho top product traffic cao mỗi 5 phút.
2. Bật nén payload JSON ở response list.
3. Bật stale-while-revalidate cho endpoint aggregate.
4. Theo dõi metrics bắt buộc:
   *   Cache hit rate
   *   P95/P99 latency đọc aggregate/list

## Tóm lược

| Yêu cầu | Giải pháp | Tác dụng |
| :--- | :--- | :--- |
| Tốc độ đọc aggregate | Redis aggregate + soft refresh | Giảm truy vấn DB nóng |
| Tối ưu page 1 | Versioned cache + INCR invalidation | Tránh scan/delete key hàng loạt |
| Chống spam vote | Rate limit trên Redis (shared across pods) | Cản spam request trước khi xuống tới DB |
| Độ tươi top helpful | Cập nhật nguyên tử DB transaction | Luôn nhất quán, không độ trễ, không cần worker |
| Tuân thủ privacy | Invalidate theo product khi anonymize | Tránh lộ tên user đã xóa tài khoản |
