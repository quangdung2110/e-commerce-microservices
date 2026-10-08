---
doc_id: user-domain
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - go/services/user-service/internal/domain/entity
  - go/services/user-service/internal/domain/service
  - go/services/user-service/internal/common/errors/errors.go
  - go/services/user-service/test/unit
verified_against_commit: null
---

# User Service — Mô hình miền nghiệp vụ

> File này trả lời: “User Service bảo vệ những quy tắc nghiệp vụ nào?”

## 1. Ngôn ngữ thống nhất

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Account | Identity, credential và trạng thái xác thực do Auth sở hữu | User profile |
| Profile | Hồ sơ nhân khẩu học do User sở hữu, định danh bằng `user_id` Auth phát | Account |
| Address | Địa chỉ nhận hàng có owner là một profile | Snapshot address trong Order |
| Default address | Address đang được chọn mặc định của một profile | Address đầu tiên theo thứ tự hiển thị |
| Preferences | Tùy chọn ngôn ngữ, tiền tệ và nhận thông báo của một profile | Notification projection |
| Anonymization | Thay thế/xóa PII để profile không còn nhận diện được | Hard delete |
| Retention record | Bản ghi bền vững để worker biết khi nào được hard delete | Kafka event |

## 2. Đối tượng miền nghiệp vụ

`UserProfile` là aggregate root, có cùng identity UUID với account ở Auth nhưng
không sở hữu account. `Address` thuộc profile và chỉ có ý nghĩa trong address
book của profile đó. `UserPreferences` có quan hệ 1:1 với profile. `UserDeletion`
là retention record độc lập phục vụ lifecycle xóa, tham chiếu profile bằng
`user_id`; Kafka không phải event store cho mục đích này.

Order chỉ giữ snapshot address tại thời điểm checkout; User không được chỉnh sửa
snapshot này. Chat/Notification/Review có thể giữ projection từ event User nhưng
không trở thành owner của profile.

## 3. Lệnh và truy vấn

| Loại | Năng lực | Tác nhân | Aggregate/năng lực |
|---|---|---|---|
| Lệnh | Khởi tạo profile | Consumer Auth event | UserProfile/Preferences |
| Truy vấn | Đọc profile của mình | Chủ sở hữu đã xác thực | UserProfile |
| Lệnh | Cập nhật profile của mình | Chủ sở hữu đã xác thực | UserProfile |
| Truy vấn | Liệt kê address của mình | Chủ sở hữu đã xác thực | Address book |
| Lệnh | Tạo, sửa, xóa, đặt address mặc định | Chủ sở hữu đã xác thực | Address book |
| Lệnh | Cập nhật preferences của mình | Chủ sở hữu đã xác thực | UserPreferences |
| Lệnh | Tắt marketing ở nguồn sau khi người dùng báo spam | Consumer Notification outcome | UserPreferences |
| Lệnh | Anonymize dữ liệu account | Consumer Auth deletion event | UserProfile/UserDeletion |
| Lệnh | Hard delete profile đến hạn | Compliance worker | UserDeletion |

## 4. Quy tắc và bất biến nghiệp vụ

| ID | Rule | Violation/error |
|---|---|---|
| `USER-BR-001` | Mọi thao tác address phải lọc đồng thời `address_id` và owner `user_id`. | `ADDRESS_NOT_FOUND` (không tiết lộ ownership) |
| `USER-BR-002` | Address đầu tiên được tạo cho profile (kể cả address đầu tiên sau khi address book đã rỗng lại) trở thành default. | N/A |
| `USER-BR-003` | Tối đa một address default cho một user. | `DEFAULT_ADDRESS_CONFLICT` (safety net của unique index; không xảy ra khi per-user lock được giữ) |
| `USER-BR-004` | Không xóa address đang là default **khi address book còn address khác**; owner phải chọn address khác làm default trước. Address duy nhất còn lại xóa được ([ADR-012](./decisions/ADR-012-delete-last-address.md)). | `DEFAULT_ADDRESS_CANNOT_BE_DELETED` |
| `USER-BR-005` | Profile/preferences bootstrap lặp lại không được overwrite chỉnh sửa đã có. | N/A; idempotent no-op |
| `USER-BR-006` | `USER_DELETED` hợp lệ phải anonymize PII, xóa address/preferences và tạo deletion record một lần. | `DELETION_ALREADY_PROCESSED` là no-op |
| `USER-BR-007` | Hard delete chỉ xảy ra khi `hard_delete_after` do Auth phát đã đến; không hỗ trợ restore profile đã anonymize. | `RETENTION_NOT_EXPIRED` |
| `USER-BR-008` | Account đang trong retention window (đã anonymize, chưa purge) không còn đọc/ghi được bởi owner: mọi route của owner coi như profile không tồn tại ([ADR-010](./decisions/ADR-010-deleted-account-invisible-to-owner.md)). | `PROFILE_NOT_FOUND` |
| `USER-BR-009` | Marketing là opt-in: preferences mới có `email_enabled=true`, `in_app_enabled=true`, `marketing_enabled=false` ([ADR-009](./decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md)). | N/A |
| `USER-BR-010` | Khi Notification báo `complaint-received`, `marketing_enabled` của user bị tắt ở nguồn một cách nguyên tử và idempotent; các cờ khác giữ nguyên. User đã xóa hoặc không có preferences thì bỏ qua. | N/A; no-op |
| `USER-BR-011` | Một profile giữ tối đa 50 address. | `INVALID_ADDRESS` |

`USER-INV-001` đến `USER-INV-007` tại [README](./README.md) là invariant cấp
service. Nơi enforce: ownership ở `AddressRepository.GetOwned` (lọc đồng thời
`address_id` và `user_id`); một default ở partial unique index cộng advisory lock theo
user; deletion terminal ở `LifecycleService.Bootstrap` (kiểm retention record) và
`ProfileRepository.GetActive*`; unit test trong `test/unit`, SQL trong
`test/integration`.

## 5. Vòng đời

Profile được tạo rỗng cùng preferences mặc định sau `USER_VERIFIED_SUCCESS`; ID
profile dùng chính `user_id` Auth phát, không tự sinh ID khác.
Profile active có thể đổi profile, address và preferences. Khi Auth phát
`USER_DELETED`, User tạo retention record, anonymize profile, hard-delete
address/preference và sau đó profile chỉ còn ở trạng thái anonymized: row vẫn tồn tại
cho tới khi purge nhưng owner không đọc hay ghi được nữa (`USER-BR-008`). Compliance
worker hard-delete profile và retention record khi deadline đến; đây là terminal
state trong User Service.

Preferences bắt đầu với email/in-app bật, marketing tắt, rồi đổi theo hai nguồn: owner
(`PUT /preferences`) hoặc complaint của Notification (chỉ tắt marketing).

Address có lifecycle `created → updated/default-changed → deleted`; hard delete
không ảnh hưởng snapshot của Order. Không có soft-delete cho address. Address book có
thể rỗng (account mới, hoặc sau khi xóa address duy nhất).

## 6. Quy tắc phân quyền

API public chỉ chấp nhận identity đã được Gateway/xác thực transport chuyển tiếp;
client không được tự gửi `user_id` để chọn resource. Owner chỉ đọc/sửa dữ liệu
của chính mình. Cổng internal nhận `user_id` từ caller, nên chỉ `checkout-service`
được gọi: service mesh thực thi principal, User kiểm thêm allowlist
`X-Service-Identity` ([ADR-008](./decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md)).
Anonymization chỉ do consumer của Auth event thực hiện; tắt marketing do complaint chỉ
do consumer của Notification outcome thực hiện; hard delete chỉ do worker. Endpoint/quyền
admin, nếu cần, phải được chốt trong OpenAPI, không suy ra từ tài liệu này.

## 7. Lỗi miền nghiệp vụ

Mọi lỗi HTTP là `application/problem+json` với `code` ổn định dưới đây
(`internal/common/errors/errors.go`, ánh xạ status ở `internal/api/httpx/problem.go`).

| Mã lỗi | Điều kiện | Có thể retry | Ánh xạ giao diện |
|---|---|---|---|
| `VALIDATION_FAILED` | Body không phải JSON hợp lệ, sai media type, field lạ (kể cả `language`/`currency` ở PUT preferences, `display_name` ở PATCH profile), path param không phải UUID | Không | HTTP 400 |
| `UNAUTHORIZED` | Thiếu hoặc không tin cậy được `X-User-Id` (public) hoặc `X-Service-Identity` (internal) | Không | HTTP 401 |
| `FORBIDDEN` | `X-Service-Identity` ngoài allowlist của listener internal | Không | HTTP 403 |
| `PROFILE_NOT_FOUND` | Profile chưa bootstrap, đã anonymize (account bị xóa, chờ purge) hoặc đã purge; cũng dùng cho preferences không còn | Không | HTTP 404 |
| `ADDRESS_NOT_FOUND` | Address không tồn tại hoặc không thuộc owner | Không | HTTP 404 |
| `NOT_FOUND` / `METHOD_NOT_ALLOWED` | Route hoặc method không có trong contract | Không | HTTP 404 / 405 |
| `DEFAULT_ADDRESS_CANNOT_BE_DELETED` | Xóa address default khi address book còn address khác | Không | HTTP 409 |
| `DEFAULT_ADDRESS_CONFLICT` | Unique index "một default" bị chạm (hai writer vượt qua per-user lock; không mong đợi) | Có thể thử lại thao tác | HTTP 409 (không nằm trong OpenAPI) |
| `INVALID_PROFILE_FIELD` | Tên rỗng/quá dài, ngày sinh ở tương lai, gender ngoài enum; kèm danh sách field | Không | HTTP 422 |
| `INVALID_ADDRESS` | Recipient/phone E.164/address line/mã địa giới sai, hoặc vượt 50 address | Không | HTTP 422 |
| `INVALID_PREFERENCE` | Preferences đã lưu mang language/currency ngoài `vi`/`VND` (lỗi dữ liệu; request không gây ra được vì body chỉ có ba cờ boolean và field lạ là `VALIDATION_FAILED`) | Không | HTTP 422 |
| `RATE_LIMITED` | Vượt rate limit của identity (public) hoặc của cặp caller + user (internal) | Có, sau `Retry-After` | HTTP 429 |
| `DEPENDENCY_UNAVAILABLE` | PostgreSQL/Redis (limiter) không trả lời; hợp đồng public không có 503 | Có | HTTP 500 (`retryable=true`) |
| `INTERNAL_ERROR` | Lỗi không mong đợi | Có | HTTP 500 |
| `DELETION_ALREADY_PROCESSED` | Event deletion trùng | Không | Consumer không làm gì/ack |
| `RETENTION_NOT_EXPIRED` | Worker xử lý trước deadline | Không | Worker bỏ qua |

Timeout, unavailable database/cache/broker là lỗi hạ tầng, không phải domain
error; hành vi nằm trong [Operations](./operations.md).

## 8. Ví dụ nghiệp vụ

- Owner có default A và address B: yêu cầu xóa A trả
  `DEFAULT_ADDRESS_CANNOT_BE_DELETED`; owner cần chọn B làm default trước.
- Owner có default A và address B: đặt B làm default trong transaction riêng,
  sau đó mới được xóa A.
- Owner chỉ có address A (default): xóa A thành công, address book rỗng; address tạo sau
  đó lại thành default.
- Complaint đến cho user có `marketing_enabled=true`: cờ tắt, `USER_PREFERENCES_UPDATED`
  được phát; complaint trùng không đổi gì và không phát event.
- `USER_VERIFIED_SUCCESS` được Kafka giao hai lần: profile/preference vẫn chỉ có
  một bản ghi, không reset các trường user đã cập nhật.
- `USER_DELETED` đến sau profile update: row profile bị khoá nên hai transaction tuần
  tự hóa. Update commit trước thì anonymization scrub luôn kết quả đó; update đến sau khi
  anonymization đã commit nhận `PROFILE_NOT_FOUND` và không ghi gì.
