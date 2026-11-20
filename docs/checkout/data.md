---
doc_id: checkout-data
service: checkout-service
scope: current
status: implemented
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - decisions/ADR-011-token-keyed-session-and-recovery.md
  - decisions/ADR-016-fresh-preview-and-in-flight-submission-guard.md
verified_against_commit: null
---

# Checkout Service — Data Design

## Nguyên tắc

Checkout Redis là kho trạng thái workflow ngắn hạn, không phải cache dùng chung của domain
khác. Mọi record được định danh theo token hash, có TTL và đổi trạng thái atomically bằng
Lua. Không có PostgreSQL, Kafka, outbox hay worker nền.

## Keyspace

| Key | Kiểu | Nội dung | TTL |
|---|---|---|---:|
| `checkout:session:{token_ref}` | Hash | Field điều khiển plaintext + snapshot mã hoá | 30 phút từ lúc tạo; claim đặt lại 30 phút từ `claimed_at` |
| `checkout:active:{user_id}` | String | `token_ref` của token chưa terminal duy nhất | Bằng TTL của session nó trỏ tới, cập nhật trong cùng script |
| `checkout:submit-payload:{token_ref}` | String | Request Order bất biến (mã hoá, chứa raw token) | 30 phút từ `claimed_at`, không gia hạn |
| `checkout:result:{token_ref}` | Hash | `user_id`, terminal `state`, `order_id`, `problem` (mã hoá) | `SUCCEEDED` 24 giờ; `CONFLICT`/`FAILED` 30 phút |
| `checkout:idem:{user_id}:{idem_ref}` | Hash | `fp` (fingerprint request) + `resp` (response Preview, mã hoá, chứa raw token) | 5 phút |

Không có key rate limit: quota thuộc Gateway. Replay một `SUCCEEDED` không cần payload vì
client gửi token trong request và Payment data luôn lấy mới từ Order.

`token_ref` = `{key_version}:{hex HMAC-SHA-256(key, "token:" + token)}`; `idem_ref` tương tự với
tiền tố `idem:`, nên hai không gian không trùng nhau. Không lưu raw token hay raw idempotency
key trong key name. Khi xoay khoá, một record được tìm dưới mọi version đang cấu hình (active
trước), nên đổi version active không làm mồ côi session còn sống.

Format cấu hình khoá: `v1:<base64>,v2:<base64>`; khoá HMAC tối thiểu 32 byte, khoá mã hoá đúng
32 byte (AES-256). Redis chạy standalone hoặc Sentinel, không Cluster: mỗi script chạm nhiều key
của cùng một user và không có hash tag.

## Session hash

| Field | Dạng | Ý nghĩa |
|---|---|---|
| `schema_version` | plaintext | `1` |
| `user_id` | plaintext | Owner check trong Lua |
| `state` | plaintext | State machine ở [domain.md](./domain.md#state-machine) |
| `expires_at` | plaintext (epoch ms) | Hạn logic của báo giá |
| `claimed_at`, `attempt_started_at`, `revalidated_at`, `last_order_call_started_at`, `attempt_id` | plaintext | Điều phối submit/recovery. `claimed_at` neo cửa sổ retry 2 phút; `attempt_started_at` (đặt lúc claim và recovery claim) neo owner timeout |
| `created_at`, `updated_at` | plaintext (epoch ms) | Lúc tạo báo giá và lần ghi trạng thái cuối; chỉ để vận hành, không có luật nào dựa vào chúng |
| `snapshot` | ciphertext | Address (PII), line items, allocation plan, promotion, tổng tiền, `items_hash`, `pricing_hash` |
| `key_version` | plaintext | Version khoá mã hoá đã dùng |

Lua chỉ đọc field plaintext nên không cần giải mã trong Redis. Snapshot, submit payload và
replay idempotency được envelope-encrypt bằng khoá có version (AES-GCM, khoá dữ liệu bọc
bởi khoá từ secret manager; wire form `e1.<kek-version>.<dek-đã-bọc>.<dữ-liệu>`, `purpose` là
authenticated data nên một ciphertext không thể chuyển sang field khác). Không lưu Payment client secret, access token, cookie, header
xác thực hay dữ liệu thẻ dưới bất kỳ dạng nào.

## Atomic operations

Mỗi thao tác là một Lua script nạp lúc khởi động và gọi bằng `EVALSHA`: `peek_preview`,
`create_preview`, `claim`, `recovery_claim`, `mark`, `to_retryable`, `finalize`.

### Tạo Preview (một script)

1. Đọc `checkout:active:{user_id}`; nếu session được trỏ tới đang `SUBMITTING`/`RETRYABLE`
   → trả `SUBMISSION_IN_PROGRESS`, không ghi gì.
2. Nếu request có `previous_checkout_token`: hash của nó phải bằng con trỏ và session phải
   `PREVIEWED`; sai → `ACTIVE_CHECKOUT_CHANGED`, không ghi gì.
3. Ghi session mới (`HSET` + `PEXPIRE`), chuyển session `PREVIEWED` cũ (nếu có) sang
   `SUPERSEDED` mà không đổi TTL, đặt con trỏ active sang token mới với cùng TTL, ghi replay
   idempotency.

Script fail không để lại session mồ côi. Hai Preview đồng thời không thể cùng tạo token
`PREVIEWED`: script sau thấy con trỏ đã đổi.

### Claim Submit (một script)

Kiểm tra owner, `state=PREVIEWED`, `now < expires_at`; rồi cùng lúc ghi `state=SUBMITTING`,
`attempt_id`, `claimed_at`, submit payload, đặt lại TTL session/payload/con trỏ thành 30 phút.
Không materialize được payload thì state không đổi. Không dùng chuỗi `SETNX`/`EXPIRE`/`DEL`
rời rạc.

### Recovery claim

`SUBMITTING` quá owner timeout hoặc `RETRYABLE` → `SUBMITTING` với `attempt_id` mới, giữ
nguyên payload. Không bao giờ quay về `PREVIEWED`.

### Hoàn tất

Ghi `checkout:result:*` và terminal state trong cùng script; reader không bao giờ thấy
`SUCCEEDED` thiếu `order_id`. Con trỏ active giữ nguyên trỏ tới token terminal cho tới khi
hết TTL hoặc bị Preview mới thay; token terminal không chặn Preview.

## Chính sách cache

Không tạo cache độc lập cho Cart, Product, User, Inventory hay Promotion, kể cả voucher
options. Những bản chụp này chỉ nằm trong session của đúng token. Thêm cache downstream cần
ADR nêu owner, key version, TTL, stale policy, invalidation, failure mode và metric.

## Eviction và mất Redis

- Redis phải chạy `maxmemory-policy noeviction`, có replica/HA và persistence (AOF
  `everysec`). Memory pressure là sự cố, không phải cache miss. Vì chính sách này áp cho cả
  server, Checkout dùng Redis riêng (`checkout-redis` trong compose), không dùng chung với
  Redis `allkeys-lru` của các service khác ([ADR-018](./decisions/ADR-018-implementation-decisions.md)).
- Báo giá quá hạn logic nhưng record còn: `410` sau owner check. Record đã mất: `404`, client
  tạo báo giá mới.
- Submission đã claim mà local state mất: client hỏi status → không còn record → `404`;
  runbook dùng Order recovery theo token để xác định đơn đã tạo hay chưa. Không tạo key mới
  cho token cũ.
- Mất Redis toàn phần: readiness `false`, Preview/Submit trả `503`, không có đường fallback.

## Quan sát

Đo số session tạo mới, kết quả CAS Preview, chuyển state, recovery outcome, độ trễ và lỗi
Redis, eviction (phải bằng 0). Label không chứa token, user ID hay SKU. Chi tiết ở
[operations.md](./operations.md#metric).
