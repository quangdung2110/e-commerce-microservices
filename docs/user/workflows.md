---
doc_id: user-workflows
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
verified_against_commit: null
---

# User Service — Luồng nghiệp vụ

> Các workflow là thiết kế mục tiêu. Deadline, retry, source path và test evidence
> cần được bổ sung trước khi đánh dấu `verified`.

## Danh mục luồng nghiệp vụ

| ID | Workflow | Trigger | Tác nhân | Trạng thái |
|---|---|---|---|---|
| `WF-USER-001` | Bootstrap profile sau identity verification | Kafka event | Auth consumer | proposed |
| `WF-USER-002` | Đọc và cập nhật profile | HTTP | Chủ sở hữu profile | proposed |
| `WF-USER-003` | Quản lý address book | HTTP | Chủ sở hữu profile | proposed |
| `WF-USER-004` | Đọc address bằng cache-aside | HTTP | Chủ sở hữu profile | proposed |
| `WF-USER-005` | Cập nhật preferences | HTTP | Chủ sở hữu profile | proposed |
| `WF-USER-006` | Anonymize sau khi xóa account | Kafka event | Auth consumer | proposed |
| `WF-USER-007` | Hard delete sau retention window | Schedule | Compliance worker | proposed |
| `WF-USER-008` | Reconcile profile/preference projection | Schedule | Projection reconciliation worker | proposed |

## WF-USER-001 — Bootstrap profile sau identity verification

**Mục tiêu:** account đã được Auth xác minh có một profile rỗng và preferences mặc
định. **Trigger/input:** `USER_VERIFIED_SUCCESS` từ Auth, keyed theo `user_id`.
**Luồng thành công:** consumer validate contract; trong transaction insert
`user_profiles(id=user_id)` và `user_preferences(user_id=user_id)` với default;
chỉ acknowledge Kafka sau commit.

**Idempotency/concurrency:** duplicate event hoặc replay dùng PK/FK/unique key và
`ON CONFLICT DO NOTHING`; không overwrite chỉnh sửa profile đã tồn tại. Nếu
`USER_DELETED` đến trước/đồng thời, ordering theo key phải được ưu tiên; nếu
profile đã có retention record, bootstrap là no-op và cần alert/reconciliation.
**Lỗi/phục hồi:** lỗi DB không ack để retry; retry cạn vào DLQ và alert. Không
có response đồng bộ cho Auth. Metric gồm consumer lag, result và duplicate count.

## WF-USER-002 — Đọc và cập nhật profile

**Mục tiêu:** owner đọc hoặc thay đổi profile của chính mình. **Điều kiện trước:**
identity từ Gateway đã xác thực và profile không bị anonymize/purge. Read query
PostgreSQL trực tiếp; cache không nằm trên critical path.

Update validate first/last name, date of birth và gender theo contract; ngày sinh
không được ở tương lai. Trong một transaction update profile. Sau commit,
producer phát `USER_PROFILE_UPDATED` chỉ với `user_id` và `sender_name` suy ra từ
tên. Duplicate PATCH với cùng trạng thái mong muốn an toàn; API contract không có
idempotency key riêng. Profile missing trả 404. Nếu Kafka publish thất bại, DB
không rollback: retry/reconciliation theo ADR-004, metric phải cho phép phát hiện
projection lag. Race với deletion phải được serialize/để update thất bại thay vì
khôi phục PII.

## WF-USER-003 — Quản lý address book

**Mục tiêu:** owner quản lý address book mà vẫn giữ đúng default-address invariant.
**Trigger:** các HTTP mutation address tại [Interfaces](./interfaces.md).

1. Adapter lấy `user_id` đã xác thực, không dùng user ID từ body/path.
2. Application validate recipient, recipient phone E.164, address line và
   `province_code`/`district_code`/`ward_code` của Việt Nam.
3. Create address: insert; nếu đây là address đầu tiên, đặt nó default trong cùng
   transaction.
4. Update/delete/set-default: truy vấn theo `(address_id, user_id)`; set-default
   clear default cũ và set address mới trong một transaction.
5. Delete default: luôn rollback và trả 409; owner phải đặt address khác làm
   default trước khi xóa address cũ.
6. Sau commit, `DEL user:caches:addresses:{user_id}`; trả HTTP success.

Partial unique index là guard cuối cùng, nhưng transaction/lock phải ngăn hai
request đồng thời tạo hai default. Cache `DEL` thất bại không rollback DB; stale
address bị giới hạn bởi TTL nhưng phải metric/alert khi invalidation lỗi. 404
không tiết lộ address của user khác. Test bắt buộc: create first address, set
default đồng thời, từ chối xóa default, retry mutation và cache unavailable.

## WF-USER-004 — Đọc address bằng cache-aside

**Mục tiêu:** trả address book cho checkout với latency thấp mà PostgreSQL vẫn là
nguồn chuẩn. API `GET` thử key `user:caches:addresses:{user_id}`. Cache hit trả
JSON list; cache miss query DB theo `is_default DESC, created_at DESC`, rồi cache
list 12 giờ. List rỗng được cache TTL ngắn để chống penetration.

Cache read/serialization error hoặc Redis unavailable fallback DB; không trả
cache bị hỏng. Cache miss đồng thời có thể gây herd vì lock/single-flight chưa
được chốt; đó là risk cần đo. Dữ liệu có thể stale giữa commit và invalidation,
hoặc đến TTL nếu `DEL` thất bại; không dùng result này cho authorization/write.
Metric: hit/miss, fallback, cache error, invalidation failure và DB query latency.

Checkout không đọc database User. Khi client chọn `address_id`, Checkout gọi
`GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` qua
mTLS để User kiểm tra ownership và trả snapshot province/district/ward sẽ được
persist vào Checkout session rồi Order.

## WF-USER-005 — Cập nhật preferences

**Mục tiêu:** owner thay đổi notification opt-in. `language` và `currency` được
cố định lần lượt là `vi` và `VND`; transaction update row 1:1 của owner, rồi phát
`USER_PREFERENCES_UPDATED` sau commit. Nếu profile/preferences không tồn tại,
trả 404 hoặc thực hiện bootstrap theo OpenAPI được chốt; thiết kế này ưu tiên 404
để tránh lặng lẽ tái tạo profile đã xóa. Duplicate state update không tạo data
duplicate; event duplicate vẫn phải được Notification deduplicate. Kafka failure
không rollback DB và cần reconciliation/metric theo ADR-004.

## WF-USER-006 — Anonymize sau khi xóa account

**Mục tiêu:** xóa PII User ngay sau khi Auth quyết định delete account. **Input:**
`USER_DELETED` có `user_id`, `deleted_at`, reason và `hard_delete_after` do Auth
quyết định.

Trong transaction, consumer create retention record idempotently; scrub name,
phone, date of birth, gender và các PII profile theo policy; hard-delete toàn bộ
address và preferences; commit. Email không được xử lý tại User vì Auth sở hữu
nó. User không phát lại event deletion: Notification, Review và Chat nhận cùng
`USER_DELETED` trực tiếp từ Auth theo [ADR-006](./decisions/ADR-006-auth-owns-account-deletion-event.md).

Duplicate `USER_DELETED` không được thay đổi deadline. DB failure thì không
ack/retry. Downstream anonymize/hide projection độc lập ngay khi nhận event trực
tiếp từ Auth, không đợi worker 90 ngày.

## WF-USER-007 — Hard delete sau retention window

**Mục tiêu:** purge profile anonymized khi `hard_delete_after <= now`.
Worker theo schedule lấy batch retention records, claim/lock từng record, kiểm
tra deadline, rồi trong transaction delete profile và deletion record. Address
và preferences đã bị xóa ở WF-USER-006; foreign-key cascade chỉ là safety net.

`user-retention-purge` chạy mỗi giờ theo UTC, lấy tối đa 100 record đến hạn theo
thứ tự `hard_delete_after`. Nhiều instance claim record bằng lock/`SKIP LOCKED`;
mỗi record có transaction riêng để crash giữa các batch vẫn resume an toàn. Worker
retry lỗi transient, metric số due/processed/failed/oldest due và alert khi backlog
vượt SLO. Record đã purge là no-op. Không hard-delete sớm và không tạo lại profile
từ event verification cũ sau purge.

## WF-USER-008 — Reconcile profile/preference projection

**Mục tiêu:** khôi phục eventual consistency của Chat và Notification khi direct
publish sau commit đã retry cạn. `user-projection-reconciliation` chạy một lần
mỗi ngày ngoài giờ cao điểm, quét toàn bộ profile và preferences hiện còn active
theo batch/cursor chỉ tồn tại trong lần chạy; không tạo checkpoint, outbox hay bảng
dedup mới.

Mỗi batch loại user đã có `user_deletions`, rồi phát current snapshot theo đúng
contract hiện tại: `USER_PROFILE_UPDATED(user_id, sender_name, updated_at)` và
`USER_PREFERENCES_UPDATED(user_id, email_enabled, in_app_enabled,
marketing_enabled, updated_at)`. Profile chưa có tên dùng fallback `Người dùng`.
Mỗi event reconciliation có CloudEvent `id` mới vì là corrective snapshot, không
phải retry delivery cũ; `updated_at` giữ nguyên từ database để consumer bỏ qua
state cũ hơn.

Worker áp dụng batch size/rate limit để không làm nghẽn PostgreSQL hoặc Kafka.
Kafka publish lỗi phải retry theo policy producer; failure cạn được metric/alert
và batch chưa hoàn tất sẽ được quét lại trong lần chạy sau. Không phát lại
`USER_DELETED`, không đọc log PII để suy đoán event, và không tự động redrive DLQ.

## Tình huống kiểm thử bắt buộc

Mỗi workflow cần happy path, invalid input, duplicate event/request, Kafka/Redis/
PostgreSQL outage, retry, timeout, concurrent default-address mutation,
out-of-order verification/deletion, retention purge lock/crash-resume và projection
reconciliation scenario.
