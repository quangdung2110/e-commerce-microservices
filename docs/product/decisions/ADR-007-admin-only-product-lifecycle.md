---
doc_id: product-adr-007
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-007 — Vòng đời admin-only ba trạng thái

## Status

`Accepted`

## Context

Nhiều nền tảng thương mại điện tử là marketplace nhiều người bán, nên vòng đời sản phẩm
thường có các trạng thái kiểm duyệt: `PENDING_REVIEW`, `REJECTED`, `BANNED`. Nền tảng
này không có seller hay shop: admin trực tiếp tạo và quản lý toàn bộ sản phẩm.

Ngoài ra cần phân biệt hai lý do khiến một sản phẩm không hiển thị: admin chủ động ẩn,
và hệ thống tự ẩn vì hết hàng. Nếu không phân biệt, hệ thống sẽ tự hiện lại sản phẩm mà
admin cố ý ẩn ngay khi có hàng về.

## Decision

Vòng đời sản phẩm chỉ có ba trạng thái: `DRAFT`, `ACTIVE`, `HIDDEN`. Không có
`PENDING_REVIEW`, `REJECTED` hay `BANNED`.

Cờ `admin_hidden` ghi nhận ý chí của admin: chỉ được set `true` khi admin gọi `HIDE`, và
được reset `false` khi admin gọi `SHOW` thành công. `AUTO_HIDE_OUT_OF_STOCK` giữ nguyên
giá trị hiện tại của cờ; `AUTO_REACTIVATE_IN_STOCK` bị cấm tuyệt đối khi cờ bằng `true`.

Xóa mềm **không** phải một state của FSM: nó là cờ `is_deleted` ở tầng dữ liệu, và khi
xóa mềm thì trạng thái nghiệp vụ được giữ nguyên.

Hai transition tự động hoạt động ở cấp product: chỉ auto-hide khi **toàn bộ** SKU hết
hàng; còn một SKU khả dụng thì product vẫn `ACTIVE`.

## Alternatives considered

- **Giữ sẵn các trạng thái kiểm duyệt cho tương lai**: sẽ đỡ công nếu sau này có
  seller, nhưng hiện tại tạo ra state chết mà mọi guard, event và consumer đều phải xử
  lý mà không bao giờ gặp.
- **Coi xóa mềm là một state `DELETED`**: gọn hơn về mô hình, nhưng làm mất trạng thái
  nghiệp vụ cuối cùng của sản phẩm và khiến consumer khó phân biệt "ẩn" với "đã xóa".
- **Không có `admin_hidden`, dùng lịch sử transition để suy ra ai đã ẩn**: bỏ được một
  cột, nhưng biến một guard đơn giản thành truy vấn lịch sử ngay trong transaction đang
  giữ lock.
- **Auto-hide ở cấp SKU thay vì cấp product**: chính xác hơn về mặt hiển thị từng biến
  thể, nhưng người mua vẫn cần thấy trang sản phẩm nếu còn ít nhất một biến thể mua được.

## Consequences

Tích cực: mô hình nhỏ, mọi transition đều có người dùng thật; quyền kiểm soát của admin
không bao giờ bị hệ thống ghi đè; Search chỉ cần phân biệt `ACTIVE` với phần còn lại.

Tiêu cực: nếu sau này nền tảng mở cho seller, sẽ phải thêm state và sửa mọi guard,
event, consumer — đây là chi phí đã chấp nhận. Cờ `admin_hidden` là state ẩn nằm ngoài
FSM nên dễ bị quên khi thêm transition mới.

Ràng buộc mới: mọi transition mới phải nói rõ nó tác động thế nào tới `admin_hidden`;
`AUTO_REACTIVATE_IN_STOCK` phải kiểm tra `admin_hidden` và `is_deleted` trong cùng
transaction, không được kiểm tra bên ngoài.

## Migration impact

Không có migration mới. Ảnh hưởng chính là ở việc mọi tài liệu và code phải nhất quán
rằng chỉ có ba trạng thái, và soft delete không sinh transition.

## Verification

Chứng minh bằng: cấu hình FSM liệt kê đúng ba state và năm transition; test khẳng định
sản phẩm có `admin_hidden = true` không bao giờ được auto-reactivate; test xóa mềm không
làm đổi `status`; và test auto-hide chỉ kích hoạt khi toàn bộ SKU hết hàng.
