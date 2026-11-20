# ADR-023 — API đọc ví voucher độc lập

- Ngày: 2026-10-20
- Trạng thái: Accepted (implemented)

## Bối cảnh

Suggestion/discovery cần checkout context và chỉ phục vụ lựa chọn voucher. Buyer chưa thể đọc đầy đủ lịch sử voucher đã sở hữu nếu không có giỏ/địa chỉ, gồm cả USED và EXPIRED.

## Quyết định

Thêm `GET /api/v1/me/promotion-vouchers`, lấy owner từ trusted authenticated identity của Gateway. Không nhận user ID để chọn ví, không thêm quyền đọc ví người khác cho Admin. Router `promotion-me` hiện có đã bao phủ endpoint.

Response `WalletPage` gồm tối đa 100 entry, mặc định 20, sắp xếp wallet ID giảm dần, keyset cursor gắn với user và status filter. Cursor là opaque continuation, không phải quyền truy cập; mọi truy vấn vẫn có điều kiện owner. Đổi owner/filter phải bắt đầu trang đầu. Cursor không đóng băng snapshot xuyên trang; redemption/expiry có thể thay đổi giữa các lần đọc.

Mỗi entry chứa ownership hiện có, định nghĩa giảm giá, đơn tối thiểu, mô tả điều kiện an toàn cho khách, cờ enabled và `effective_status`. Không trả budget quản trị, raw rule AST hay danh sách UUID người nhận trong rule. Điều kiện danh mục được mô tả ở mức phạm vi; eligibility chính thức vẫn được kiểm tra tại checkout.

Lấy một thời điểm PostgreSQL `as_of` cho mỗi page. `SAVED` có `expired_at <= as_of` hiển thị/lọc là `EXPIRED`; `USED` vẫn là `USED` kể cả qua hạn. Không cập nhật row khi GET: `wallet.status`/`updated_at` là giá trị durable, `effective_status` là projection. Điều này không can thiệp Confirm hợp lệ chuyển EXPIRED sang USED khi payment/reservation đúng hạn theo ADR-019.

Không dùng campaign phase/end để thu hồi wallet đã sở hữu. Hạn dùng lấy từ `wallet.expired_at` đã snapshot. `is_enabled` là cờ definition, không hứa đủ ngân sách hoặc đáp ứng rule của một đơn chưa biết.

Query chỉ cần PostgreSQL, join bounded page với definition; không đọc Redis, Product, User, cart hoặc address. Index `(user_id, id DESC)` hỗ trợ lịch sử; GET không grant/save/claim/reserve. HTTP trả `Cache-Control: no-store`; frontend scope cache bằng account + filter và đọc lại khi mở/focus/lưu voucher.

## Phạm vi

Trang `/vouchers` chỉ hiển thị ví đã sở hữu và dẫn đến giỏ/đơn đã dùng. Không thêm catalog voucher công khai không cần context, nhập mã marketing, chuyển nhượng hoặc auto-apply từ ví. Checkout tiếp tục dùng Preview-owned voucher-options và quyết định giá chính thức.
