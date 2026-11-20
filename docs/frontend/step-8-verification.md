# Frontend Step 8 Verification — Admin Orders & Physical Dispatch

> **Cập nhật live 11/10/2026:** xem [báo cáo bổ sung](./live-local-2026-10-11.md) và checkbox hiện hành trong implementation-plan. Các kết quả bên dưới là lịch sử kiểm chứng; blocker Stripe/CDC và một số mutation đã được kiểm tra lại.

Ngày cập nhật: **06/10/2026**.

## Phạm vi đã triển khai

- Danh sách `/admin/orders`: tìm kiếm, lọc người mua, Buyer tab, trạng thái đơn,
  trạng thái thanh toán, thời gian và số tiền; bộ lọc nằm trên URL, cursor nằm trong
  state của bảng admin.
- Chi tiết `/admin/orders/{id}`: snapshot theo kiện/kho, dòng hàng, địa chỉ, người mua,
  tiền, thanh toán/refund và liên kết trực tiếp tới phân bổ Inventory.
- Admin cancellation: chỉ mở khi `PENDING_PAYMENT`, body cố định
  `ADMIN_FORCE_CANCEL`, idempotency key và poll admin-scoped Order operation.
- Admin pre-dispatch refund: chỉ mở khi `PAID`/`PROCESSING`, chưa có refund case và
  mọi line còn `PROCESSING`; nhận reason code/details, poll operation và giữ đúng ngữ
  nghĩa “attempt có thể bị Inventory reject”.
- Phân bổ `/admin/inventory/reservations/{orderId}`: root/line state, revision,
  warehouse/SKU/quantity và nút dispatch chỉ cho line `COMMITTED`.
- Physical dispatch gửi nguyên allocation cùng dispatch ID/line ID, thời điểm bàn giao,
  handover reference, evidence reference và đủ ba header command của Inventory.

## Contract và backend đã đối chiếu

- `contracts/http/order/v1/openapi.yaml`: admin list/detail/cancellation,
  pre-dispatch refund và admin operation.
- `contracts/http/inventory/admin/v1/openapi.yaml`: reservation fact và
  physical-dispatch command; partial-line dispatch không tồn tại.
- `OrderCommandService` chỉ cho cancel ở `PENDING_PAYMENT`, refund attempt ở
  `PAID`/`PROCESSING`; `DispatchService` kiểm tra line còn `COMMITTED` và toàn bộ
  warehouse/SKU/quantity khớp phân bổ.
- UI chỉ là guard trải nghiệm. Backend tiếp tục quyết định ở thời điểm nhận lệnh để
  xử lý race giữa release và dispatch.

## Bằng chứng offline

| Gate | Kết quả |
|---|---|
| TypeScript | `tsc --noEmit` qua |
| Unit/component tests | 18 file, 114 test qua; thêm coverage API admin, Inventory dispatch, navigation và action guard |
| ESLint | 0 lỗi; 3 cảnh báo có sẵn ngoài code bước 8 |
| Production build | Next.js 16.2.9 build qua; ba route bước 8 xuất hiện trong route manifest |

Build lần đầu trong sandbox không tải được Google Fonts do network bị chặn. Chạy lại với
quyền network cho build đã compile, typecheck, prerender và hoàn tất thành công.

## Kiểm chứng live trên Docker Desktop

Stack local được kiểm tra ngày 06/10/2026 qua gateway `:8088` và frontend dev `:3000`.
Inventory Service ban đầu dừng, sau khi khởi động lại đã healthy. Tài khoản
`admin@technova.test` đăng nhập được với role `ADMIN`; tài khoản Buyer gọi
`GET /api/v1/admin/orders` nhận đúng `403`.

| Trường hợp | Kết quả |
|---|---|
| Danh sách admin | Hiển thị 3 đơn toàn hệ thống; URL filter, empty state và detail navigation hoạt động |
| Tìm kiếm | `q=Terra 75` trả đúng đơn `770002`. Placeholder đã đổi thành “Tên sản phẩm” vì contract chỉ tìm snapshot tên sản phẩm, không tìm mã đơn |
| Chi tiết và guard | `770001` cho cancel; `770002` đã dispatch khoá cả cancel/refund; dữ liệu kiện, line, buyer và tiền hiển thị đúng |
| Cancel operation | UI gửi lệnh, poll và sau timeout diễn đạt đúng “lệnh vẫn đang chạy”; operation `01a110bb-22c3-72ef-a88c-6a44843d05e8` ở `ACCEPTED`, order ở `CANCELLING` |
| Physical dispatch | Fixture `qa-step8-20261006171820` chuyển revision 2 → 3, root/line `COMMITTED` → `DISPATCHED`; movement `01a110bd-3d45-79c8-a9e6-8daf3c0a68f8` được tạo |
| Tồn kho | SKU `sku-live-20261005013945`: `on_hand` 8 → 7, `available_to_sell` 7, `reserved` 0, `committed` 1 → 0 |
| Idempotency | Replay command `b89c9322-2af5-46f7-b5af-ee5c146e9f7f` trả `replayed=true`, cùng movement, tồn kho không đổi |
| Backend authority | Command mới trên line đã `DISPATCHED` bị `409 DISPATCH_NOT_ALLOWED`; tồn kho không đổi |
| Console/accessibility | Live console phát hiện các Link render qua Base UI Button thiếu `nativeButton={false}`; đã sửa ở cả ba màn hình bước 8 và reload xác nhận 0 console error |
| Regression | Typecheck qua; 18 test file / 114 test qua sau bản sửa live |

Đơn `770001` là fixture chèn trực tiếp và không có `order_workflow_dispatches`, nên không
thể dùng nó để kết luận saga cancel tới `CANCELLED`. Đây là giới hạn fixture/backend,
không phải UI báo thành công sai: UI dừng polling và giữ trạng thái đang chạy. Dữ liệu
seed cũng lưu `snapshot_name_search` còn dấu, nên tìm một cụm chỉ gồm phần có dấu như
“Bàn phím” không mô phỏng đúng normalizer của đơn được tạo qua service; cụm `Terra 75`
đã xác minh đường tìm kiếm end-to-end.

## Còn cần kiểm chứng

1. Tạo đơn qua checkout workflow thật để cancel chạy tới `CANCELLED` và replay operation.
2. Đơn đã trả tiền nhưng chưa dispatch để chạy pre-dispatch refund, gồm race giữa
   atomic release và dispatch.
3. Visual QA đủ 375/768/1280 px, light/dark, bàn phím và screen reader. Phiên live này
   đã kiểm tra trực quan viewport hẹp hiện tại và trạng thái dispatch thành công.
