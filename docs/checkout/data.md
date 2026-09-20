---
title: Checkout Data and Caching
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - decisions/ADR-011-token-keyed-session-and-recovery.md
tags: [checkout, redis, caching]
---

# Checkout Data and Caching

## Nguyên tắc

Checkout Redis là durable-enough workflow store trong thời gian ngắn, không phải cache dùng chung của domain khác. Mọi record phục vụ submit được định danh theo token, có TTL và state transition atomically.

## Keyspace

| Key | Nội dung | TTL mặc định |
|---|---|---:|
| `checkout:session:{token_hash}` | Session snapshot + state | logical expiry 20 phút; physical retention 30 phút khi chưa submit |
| `checkout:active:{user_id}` | Token hash mới nhất cho UX | 20 phút |
| `checkout:submit-payload:{token_hash}` | Order request bất biến + idempotency key | 30 phút, 24 giờ khi success |
| `checkout:result:{token_hash}` | Terminal result/problem | success 24 giờ; conflict/failure 30 phút |
| `checkout:idem:{user_id}:{idem_hash}` | Encrypted Preview response replay + request fingerprint | 5 phút |
| `checkout:rate:{user_id}:{bucket}` | Rate counter | 60 giây |

`token_hash` dùng keyed HMAC-SHA-256 với server key/version, không dùng hash trần cho token có entropy không chắc chắn. `idem_hash` không lưu raw client idempotency key.

## Session schema tối thiểu

```json
{
  "schema_version": 1,
  "state": "PREVIEWED",
  "user_id": "uuid",
  "items_hash": "sha256:...",
  "address_snapshot": {},
  "line_items": [],
  "allocation_plan": [],
  "promotion_quote": null,
  "subtotal": 100000,
  "discount_total": 0,
  "total": 100000,
  "currency": "VND",
  "pricing_expires_at": "RFC3339",
  "created_at": "RFC3339",
  "updated_at": "RFC3339"
}
```

Không lưu raw token trong key/session/log. Preview idempotency response và Submit payload
đều chứa `checkout_token`, nên các Redis value này phải được envelope-encrypt bằng
versioned key. Không lưu payment/client secret, access token, cookie, full auth header
hoặc dữ liệu thẻ.

## Atomic operations

### Tạo preview

Một transaction/script phải:

1. Kiểm tra active pointer bằng CAS: `previous_checkout_token` phải đúng active
   `PREVIEWED`; request không có previous chỉ được tạo khi không có active
   `PREVIEWED` (active đang submit/terminal không bị supersede).
2. `SET NX` session mới với TTL chỉ trong cùng script sau khi CAS hợp lệ.
3. Lưu idempotency replay cùng response digest và cập nhật active pointer.
4. Chỉ chuyển previous session từ `PREVIEWED` sang `SUPERSEDED`; không chạm token đang submit/terminal.

CAS fail không để lại session mồ côi và trả `409 ACTIVE_CHECKOUT_CHANGED`. Nhờ đó hai
Preview đồng thời không tạo hai token cùng ở trạng thái có thể Submit.

### Claim submit

Một script duy nhất phải kiểm tra owner/state/expiry rồi cùng lúc ghi:

- `state=SUBMITTING`;
- `submission_attempt_id`;
- `submission_claimed_at` và configurable owner timeout;
- Order idempotency key bằng UUID checkout token;
- submit payload hoặc immutable payload digest + record payload;
- recovery TTL.

Nếu payload không thể được materialize thì state không được đổi. Không dùng chuỗi `SETNX`, `EXPIRE`, `DEL` rời rạc và không release lock bằng raw `DEL`.
`SUBMITTING` quá owner timeout không được reset về `PREVIEWED`; recovery phải hỏi Order
trước, rồi CAS claim lại đúng encrypted payload/token và cùng idempotency key.

### Hoàn tất

Ghi metadata result trước hoặc atomically với terminal state. Result giữ `user_id`, terminal state và `order_id` để status vẫn kiểm tra ownership; reader không được thấy `SUCCEEDED` mà thiếu `order_id`. Payment client data không nằm trong result; replay/status phải lấy mới từ Order.

## Chính sách cache

Không tạo cache độc lập cho Cart, Product, User/address, Inventory availability/allocation hoặc Promotion quote. Những bản chụp này chỉ nằm bên trong session và không được tái sử dụng cho token khác.

Nếu tương lai cần cache downstream data, phải có ADR nêu owner, key version, TTL, stale policy, invalidation signal, failure mode và metrics; mặc định hiện tại là không cache.

## Eviction và mất Redis

- Preview quá logical expiry nhưng record còn retention grace: trả `410` sau owner check.
- Preview chưa submit đã physical eviction: trả `404`; client preview lại.
- Submit đã materialize payload nhưng local state mất: query Order bằng checkout token/idempotency key nếu có thể khôi phục an toàn; không tạo key mới.
- Nếu không thể chứng minh không có Order, trả `SUBMISSION_RESULT_UNKNOWN` và vận hành recovery, không khuyến khích submit mới.
- Redis phải cấu hình eviction/HA phù hợp với workflow keys; memory pressure là sự cố, không phải cache miss bình thường.

## Quan sát

Đo `session_created`, CAS conflict, active pointer overwrite, submission state count, recovery lookup, result unknown, Redis latency/error, key eviction và TTL remaining. Labels không chứa token/user ID raw.
