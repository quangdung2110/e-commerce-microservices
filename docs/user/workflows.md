---
doc_id: user-workflows
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - go/services/user-service/internal/domain/service
  - go/services/user-service/internal/infrastructure/kafka/consumer.go
  - go/services/user-service/test/unit/service
  - go/services/user-service/test/integration
verified_against_commit: null
---

# User Service — Luồng nghiệp vụ

> Các workflow khớp `go/services/user-service` và có unit/integration test. Broker E2E
> và metric production chưa có bằng chứng, nên tài liệu chưa đạt `verified`.

## Danh mục luồng nghiệp vụ

| ID | Workflow | Trigger | Tác nhân | Trạng thái |
|---|---|---|---|---|
| `WF-USER-001` | Bootstrap profile sau identity verification | Kafka event | Event consumer (Auth) | implemented |
| `WF-USER-002` | Đọc và cập nhật profile | HTTP | Chủ sở hữu profile | implemented |
| `WF-USER-003` | Quản lý address book | HTTP | Chủ sở hữu profile | implemented |
| `WF-USER-004` | Đọc address bằng cache-aside | HTTP | Chủ sở hữu profile | implemented |
| `WF-USER-005` | Cập nhật preferences | HTTP | Chủ sở hữu profile | implemented |
| `WF-USER-006` | Anonymize sau khi xóa account | Kafka event | Event consumer (Auth) | implemented |
| `WF-USER-007` | Hard delete sau retention window | Schedule | Compliance worker | implemented |
| `WF-USER-008` | Reconcile profile/preference projection | Schedule | Projection reconciliation worker | implemented |
| `WF-USER-009` | Tắt marketing sau complaint | Kafka event | Event consumer (Notification) | implemented |

## WF-USER-001 — Bootstrap profile sau identity verification

**Mục tiêu:** account đã được Auth xác minh có một profile rỗng và preferences mặc
định (email/in-app bật, **marketing tắt**). **Trigger/input:** `USER_VERIFIED_SUCCESS`
từ Auth, keyed theo `user_id`. **Luồng thành công:** consumer validate contract; trong
transaction kiểm retention record, rồi insert `user_profiles(id=user_id)` và
`user_preferences(user_id=user_id)` với default; chỉ acknowledge Kafka sau commit. Không
phát event nào: Notification coi thiếu preferences là cùng giá trị mặc định.

**Idempotency/concurrency:** duplicate event hoặc replay dùng PK/FK/unique key và
`ON CONFLICT DO NOTHING`; không overwrite chỉnh sửa profile đã tồn tại. Nếu
`USER_DELETED` đến trước/đồng thời, deletion là terminal: profile đã có retention
record thì bootstrap là no-op (`skipped_deleted`), ghi log cảnh báo và không tạo lại
profile. **Lỗi/phục hồi:** lỗi DB không ack để retry (tối đa 10 lần, backoff 1s→5m);
retry cạn hoặc message không hợp lệ vào `t.auth.events.dlq` và alert. Không có response
đồng bộ cho Auth. Log có `duplicate`/`skipped_deleted`; metric Prometheus chưa export.

## WF-USER-002 — Đọc và cập nhật profile

**Mục tiêu:** owner đọc hoặc thay đổi profile của chính mình. **Điều kiện trước:**
identity từ Gateway đã xác thực và profile không bị anonymize/purge. Read query
PostgreSQL trực tiếp (`GetActive`); cache không nằm trên critical path.

Update validate first/last name, date of birth và gender theo contract; ngày sinh
không được ở tương lai, `display_name` và field lạ bị `400`. Trong một transaction:
khoá row profile, kiểm retention record, áp merge patch, ghi nếu có thay đổi. Sau
commit, producer phát `USER_PROFILE_UPDATED` với `user_id`, `sender_name` suy ra từ tên
và `updated_at` do PostgreSQL ghi. Patch không đổi gì thì không ghi và không phát event.
Duplicate PATCH với cùng trạng thái mong muốn an toàn; API contract không có
idempotency key riêng. Profile missing, đã anonymize hoặc đã purge trả 404. Nếu Kafka
publish thất bại, DB không rollback: retry/reconciliation theo ADR-004, log cho phép
phát hiện projection lag. Race với deletion được tuần tự hóa bằng row lock và update đến
sau anonymization nhận 404 ([ADR-010](./decisions/ADR-010-deleted-account-invisible-to-owner.md)),
nên không khôi phục được PII.

## WF-USER-003 — Quản lý address book

**Mục tiêu:** owner quản lý address book mà vẫn giữ đúng default-address invariant.
**Trigger:** các HTTP mutation address tại [Interfaces](./interfaces.md).

1. Adapter lấy `user_id` đã xác thực, không dùng user ID từ body/path.
2. Application validate recipient, recipient phone E.164, address line và
   `province_code`/`district_code`/`ward_code` của Việt Nam.
3. Mọi mutation mở transaction và lấy advisory lock theo user trước tiên.
4. Create address: kiểm profile còn active (account đã xóa nhận 404), đếm address (đã đủ 50
   thì 422 `INVALID_ADDRESS`), insert; nếu address book đang rỗng, đặt nó default trong cùng
   transaction.
5. Update/delete/set-default: truy vấn theo `(address_id, user_id)`; set-default
   clear default cũ và set address mới trong một transaction.
6. Delete: nếu address là default và address book còn address khác thì rollback và trả
   409; owner phải đặt address khác làm default trước. Nếu đó là address duy nhất còn lại
   thì xóa được và address book rỗng ([ADR-012](./decisions/ADR-012-delete-last-address.md)).
7. Sau commit, script Lua tăng epoch và `DEL user:caches:addresses:{user_id}`; trả HTTP
   success.

Partial unique index là guard cuối cùng; advisory lock ngăn hai request đồng thời tạo hai
default. Cache invalidation thất bại không rollback DB; stale address bị giới hạn bởi TTL
và được ghi log mức error để cảnh báo. 404 không tiết lộ address của user khác. Test:
create first address, từ chối xóa default khi còn address khác, xóa address duy nhất,
vượt giới hạn, ownership, cache unavailable (unit) và unique-index/lock (integration).

## WF-USER-004 — Đọc address bằng cache-aside

**Mục tiêu:** trả address book cho checkout với latency thấp mà PostgreSQL vẫn là
nguồn chuẩn. API `GET` thử key `user:caches:addresses:{user_id}`. Cache hit trả
JSON list; cache miss đọc epoch của user, query DB theo `is_default DESC, created_at DESC`,
rồi fill có điều kiện: chỉ ghi khi epoch chưa đổi, TTL 12 giờ
([ADR-011](./decisions/ADR-011-address-cache-invalidation-epoch.md)). List rỗng chỉ được
cache (TTL 15 phút) sau khi xác nhận profile còn active, nên user không tồn tại hoặc đã xóa
nhận 404 thay vì 200 rỗng.

Cache read/serialization error hoặc Redis unavailable fallback DB; không trả
cache bị hỏng, và nếu không đọc được epoch thì không fill. Mutation commit và invalidate
giữa lúc reader đọc DB và fill làm epoch đổi nên fill muộn bị từ chối. Cache miss đồng
thời vẫn có thể gây herd vì single-flight chưa có; đó là risk cần đo. Dữ liệu có thể stale
giữa commit và invalidation, hoặc đến TTL nếu invalidation thất bại; không dùng result này
cho authorization/write. Log ghi cache read failure và invalidation failure; metric
Prometheus chưa export.

Checkout không đọc database User. Khi client chọn `address_id`, Checkout gọi
`GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` trên listener
internal (mesh thực thi mTLS và principal; xem [ADR-008](./decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md))
để User kiểm tra ownership trên PostgreSQL và trả snapshot province/district/ward sẽ được
persist vào Checkout session rồi Order.

## WF-USER-005 — Cập nhật preferences

**Mục tiêu:** owner thay đổi notification opt-in. `language` và `currency` được
cố định lần lượt là `vi` và `VND`; transaction update row 1:1 của owner, rồi phát
`USER_PREFERENCES_UPDATED` sau commit — chỉ khi một trong ba cờ thật sự đổi (PUT cùng body
không ghi, không phát event và không làm trigger đổi `updated_at`). Nếu preferences không
tồn tại (chưa bootstrap, account đã xóa hoặc đã purge) trả 404 `PROFILE_NOT_FOUND`, không
bootstrap lại, để tránh lặng lẽ tái tạo profile đã xóa. Marketing mặc định tắt cho tới khi
owner bật. Duplicate state update không tạo data duplicate; event duplicate vẫn phải được
Notification deduplicate. Kafka failure không rollback DB và cần reconciliation/log theo
ADR-004.

## WF-USER-006 — Anonymize sau khi xóa account

**Mục tiêu:** xóa PII User ngay sau khi Auth quyết định delete account. **Input:**
`USER_DELETED` có `user_id`, `deleted_at`, reason và `hard_delete_after` do Auth
quyết định.

Trong transaction, consumer lấy advisory lock theo user (cùng khoá với address mutation,
để một address create đang chạy phải commit trước khi address book bị xóa sạch); create
retention record idempotently; scrub name, date of birth và gender của profile (profile
không có phone); hard-delete toàn bộ address và preferences; commit. Sau commit,
cache address bị invalidate. Từ lúc đó owner nhận 404 trên mọi route
([ADR-010](./decisions/ADR-010-deleted-account-invisible-to-owner.md)). Email không được
xử lý tại User vì Auth sở hữu nó. User không phát lại event deletion: Notification, Review, Chat và Cart nhận cùng
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

## WF-USER-009 — Tắt marketing sau complaint

**Mục tiêu:** khi người dùng đánh dấu email là spam, nguồn sự thật của consent phải nói
marketing đã tắt, không chỉ bản replicate trong Notification. **Trigger/input:** event
`com.ecommerce.notification.complaint-received.v1` trên `t.notification.outcomes.v1`, keyed
theo `data.user_id`; các outcome khác trên topic (`delivered`, `bounced`, `failed`) được ack
mà không giải mã.

**Luồng:** consumer validate envelope (`source=/services/notification`) và `user_id`; chạy
một `UPDATE user_preferences SET marketing_enabled = FALSE WHERE user_id = $1 AND
marketing_enabled RETURNING ...`; nếu có row đổi thì sau commit phát
`USER_PREFERENCES_UPDATED` với `updated_at` do trigger ghi, giữ nguyên email/in-app; ack
sau khi commit.

**Idempotency/concurrency:** cờ đã tắt, user chưa bootstrap, đã xóa hoặc đã purge → câu lệnh
không đổi row nào, không phát event, vẫn ack (retry không bao giờ tạo ra preferences).
Câu UPDATE đơn lẻ nên không ghi đè cờ email/in-app mà owner đổi cùng lúc. Notification đã
tắt marketing ở bản local với version là thời điểm của provider event; `updated_at` User ghi
sau đó nên thường lớn hơn và Notification áp dụng nó, còn nếu không thì bản local đã là
`marketing_enabled=false` — hai bên hội tụ ở cùng giá trị. Nhờ đó, các snapshot cũ
(reconciliation hằng ngày hoặc một lần đổi preference sau này) không còn bật lại marketing
cho người đã báo spam. **Lỗi/phục hồi:** như WF-USER-001; message
không hợp lệ vào `t.notification.outcomes.v1.dlq`, tách khỏi DLQ của Auth.

## Tình huống kiểm thử bắt buộc

Mỗi workflow cần happy path, invalid input, duplicate event/request, Kafka/Redis/
PostgreSQL outage, retry, timeout, concurrent default-address mutation,
out-of-order verification/deletion, retention purge lock/crash-resume và projection
reconciliation scenario. Hiện được phủ bởi `test/unit` (use case với in-memory double,
consumer routing/retry/DLQ) và `test/integration` (SQL, unique index, cache, HTTP, internal
listener, backfill migration). Còn thiếu: broker E2E thật và test race song song trên
PostgreSQL cho create-address đồng thời với anonymization.
