---
doc_id: user-domain
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
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
| Lệnh | Anonymize dữ liệu account | Consumer Auth deletion event | UserProfile/UserDeletion |
| Lệnh | Hard delete profile đến hạn | Compliance worker | UserDeletion |

## 4. Quy tắc và bất biến nghiệp vụ

| ID | Rule | Violation/error |
|---|---|---|
| `USER-BR-001` | Mọi thao tác address phải lọc đồng thời `address_id` và owner `user_id`. | `ADDRESS_NOT_FOUND` (không tiết lộ ownership) |
| `USER-BR-002` | Address đầu tiên được tạo cho profile trở thành default. | N/A |
| `USER-BR-003` | Tối đa một address default cho một user. | `DEFAULT_ADDRESS_CONFLICT` |
| `USER-BR-004` | Không xóa address đang là default; owner phải chọn address khác làm default trước. | `DEFAULT_ADDRESS_CANNOT_BE_DELETED` |
| `USER-BR-005` | Profile/preferences bootstrap lặp lại không được overwrite chỉnh sửa đã có. | N/A; idempotent no-op |
| `USER-BR-006` | `USER_DELETED` hợp lệ phải anonymize PII, xóa address/preferences và tạo deletion record một lần. | `DELETION_ALREADY_PROCESSED` là no-op |
| `USER-BR-007` | Hard delete chỉ xảy ra khi `hard_delete_after` do Auth phát đã đến; không hỗ trợ restore profile đã anonymize. | `RETENTION_NOT_EXPIRED` |

`USER-INV-001` đến `USER-INV-006` tại [README](./README.md) là invariant cấp
service. Nơi enforcement chi tiết cần được xác minh trước khi tài liệu đạt
`verified`.

## 5. Vòng đời

Profile được tạo rỗng cùng preferences mặc định sau `USER_VERIFIED_SUCCESS`; ID
profile dùng chính `user_id` Auth phát, không tự sinh ID khác.
Profile active có thể đổi profile, address và preferences. Khi Auth phát
`USER_DELETED`, User tạo retention record, anonymize profile, hard-delete
address/preference và sau đó profile chỉ còn ở trạng thái anonymized. Compliance
worker hard-delete profile và retention record khi deadline đến; đây là terminal
state trong User Service.

Address có lifecycle `created → updated/default-changed → deleted`; hard delete
không ảnh hưởng snapshot của Order. Không có soft-delete cho address.

## 6. Quy tắc phân quyền

API public chỉ chấp nhận identity đã được Gateway/xác thực transport chuyển tiếp;
client không được tự gửi `user_id` để chọn resource. Owner chỉ đọc/sửa dữ liệu
của chính mình. Anonymization chỉ do consumer của Auth event thực hiện; hard
delete chỉ do worker có service credential. Endpoint/quyền admin, nếu cần, phải
được chốt trong OpenAPI, không suy ra từ thiết kế này.

## 7. Lỗi miền nghiệp vụ

| Mã lỗi | Điều kiện | Có thể retry | Ánh xạ giao diện |
|---|---|---|---|
| `PROFILE_NOT_FOUND` | Profile chưa bootstrap hoặc đã purge | Không | HTTP 404 |
| `ADDRESS_NOT_FOUND` | Address không tồn tại hoặc không thuộc owner | Không | HTTP 404 |
| `DEFAULT_ADDRESS_CANNOT_BE_DELETED` | Xóa address đang là default | Không | HTTP 409 |
| `INVALID_PREFERENCE` | Language/currency/flag không thuộc giá trị hỗ trợ | Không | HTTP 422 |
| `DELETION_ALREADY_PROCESSED` | Event deletion trùng | Không | Consumer không làm gì/ack |
| `RETENTION_NOT_EXPIRED` | Worker xử lý trước deadline | Không | Worker bỏ qua |

Timeout, unavailable database/cache/broker là lỗi hạ tầng, không phải domain
error; hành vi nằm trong [Operations](./operations.md).

## 8. Ví dụ nghiệp vụ

- Owner có address default A: yêu cầu xóa trả
  `DEFAULT_ADDRESS_CANNOT_BE_DELETED`; owner cần chọn B làm default trước.
- Owner có default A và address B: đặt B làm default trong transaction riêng,
  sau đó mới được xóa A.
- `USER_VERIFIED_SUCCESS` được Kafka giao hai lần: profile/preference vẫn chỉ có
  một bản ghi, không reset các trường user đã cập nhật.
- `USER_DELETED` đến sau profile update: deletion transaction thắng nếu commit
  sau; request update phải bị từ chối hoặc không tìm thấy profile đã anonymize.
