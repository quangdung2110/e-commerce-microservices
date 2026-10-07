# Bước 3 — Admin Kho tối thiểu

> **Trạng thái (05/10/2026): hoàn thành code, kiểm thử offline và kiểm chứng live local.**
>
> Inventory Service và route `/api/v1/admin/inventory/**` đã chạy thật qua gateway.
> MFA vẫn là production gate vì Auth local chưa phát hành MFA assertion; kiểm chứng local
> dùng role `ADMIN` theo cấu hình dev, không hạ rào xác thực và không dùng endpoint giả.

## 1. Phạm vi đã hoàn thành

| Route | Năng lực |
|---|---|
| `/admin/inventory` | Lối tắt lệnh và tra cứu theo mã kho, cặp kho/SKU, movement, command |
| `/admin/inventory/warehouses/new` | Tạo kho với địa chỉ, trạng thái, ưu tiên, lý do và bằng chứng |
| `/admin/inventory/warehouses/[warehouseId]` | Đọc và cập nhật warehouse master data |
| `/admin/inventory/stock/[warehouseId]/[skuId]` | Đọc năm bucket, revision và kiểm tra phương trình bucket trên UI |
| `/admin/inventory/receipts/new` | `INITIAL_STOCK_LOAD` hoặc `SUPPLIER_RECEIPT` với trường chứng từ đúng từng nhánh |
| `/admin/inventory/adjustments/new` | Điều chỉnh tăng/giảm với reason enum; `DATA_CORRECTION` bắt buộc ghi chú |
| `/admin/inventory/movements/[movementId]` | Đọc movement/delta và tạo reversal append-only |
| `/admin/inventory/commands/[commandId]` | Đọc kết quả bền vững của một command ID |

Không thêm API danh sách không có trong contract. Reservation/physical dispatch thuộc
bước 8; Refund Scope, receipt hàng trả, inspection và quarantine thuộc bước 12.

## 2. Quy tắc contract

- `src/lib/api/inventory-admin.ts` chỉ dùng type sinh từ
  `contracts/http/inventory/admin/v1/openapi.yaml`.
- Mọi mutation gửi `Idempotency-Key`, `X-Correlation-ID` và `X-Causation-ID`; lệnh
  khởi đầu từ admin dùng command ID cho cả ba giá trị.
- `useCommand` giữ cùng command ID cho network/`503` retry. Nếu payload được sửa sau
  lỗi, form gọi `startNewIntent()` trước lần gửi tiếp theo. Khi backend trả
  `Retry-After`, nút submit bị khóa và hiển thị đếm ngược trước khi cho retry.
- Response thành công hiện command, operation, movement và trạng thái `replayed` để
  admin có thể đối chiếu. Command lookup phân biệt kết quả lệnh ban đầu với trạng thái
  resource hiện tại.
- `403` trên màn Inventory giải thích yêu cầu ADMIN + MFA. Không tự bịa luồng MFA khi
  Auth chưa có contract tương ứng.
- `409` là xung đột nghiệp vụ bền vững; `503` là lỗi retryable và phải dùng lại đúng
  command ID/payload.

## 3. Kiểm thử offline

| Hạng mục | Kết quả |
|---|---|
| TypeScript | `tsc --noEmit` qua |
| Unit/component test | 16 file, 101 test qua; gồm endpoint/header/URL encoding, direction, thông báo MFA và countdown `Retry-After` |
| ESLint | 0 lỗi; 3 cảnh báo có sẵn ngoài bước 3 |
| Production build | Next.js 16.2.9 webpack build qua; sinh đủ tám route Inventory |
| Terra visual QA | 375/768/1280 px, light/dark, không tràn ngang; route QA tạm đã xoá sau kiểm tra |
| Browser console | Sửa cảnh báo Base UI của link render như Button bằng `nativeButton={false}` |

Build trực tiếp trên host dùng Node runtime của Codex. Next.js 16.2.9 webpack build hoàn
thành với toàn bộ route Inventory.

## 4. Kết quả kiểm chứng live

Kiểm chứng ngày 05/10/2026 qua gateway, `gateway-auth`, Auth Service và Inventory
Service thật:

- [x] Local: `401` khi thiếu identity, header giả không vượt được gateway, `403` với
  tài khoản không có role và `200` với `ADMIN`.
- [x] Tạo kho rồi đọc lại đúng name/address/state/priority/revision; update gửi
  `expected_revision` và tăng revision từ 1 lên 2.
- [x] Cùng command ID + cùng body trả replay chính xác; cùng ID + body khác trả
  `409 IDEMPOTENCY_KEY_REUSED`; stale revision trả `409 WAREHOUSE_CONFLICT`.
- [x] Write-freeze trả `503 INVENTORY_UNAVAILABLE`, `retryable: true` và
  `Retry-After: 5`; frontend giữ nguyên command ID và khóa retry theo countdown.
- [x] Initial load yêu cầu baseline batch; supplier receipt yêu cầu GRN; duplicate
  receipt line không tạo movement thứ hai.
- [x] Adjustment reason/direction guard, negative adjustment không lấy từ protected
  bucket và `DATA_CORRECTION` thiếu note bị từ chối.
- [x] Movement reversal đúng sequence/quantity/reason và không sửa movement cũ.
- [x] Stock position giữ invariant
  `available_to_sell + reserved + committed + quarantine = on_hand`.
- [x] Command query đọc được cả kết quả `ACCEPTED` và `REJECTED` bền vững.
- [x] UI thật tạo warehouse `01a10849-2835-73a2-b703-61e20ce30c08`, sau đó cập nhật
  tên/priority thành công từ revision 1 lên 2; receipt và adjustment cũng chạy qua UI.
- [x] Admin shell thật đã kiểm tra ở 375/768/1280 px, light/dark, không tràn ngang;
  console không còn warning Base UI.

### Gate triển khai production

- [ ] Auth/gateway phát hành và xác thực MFA assertion cho `ADMIN`. Đây là phụ thuộc
  production còn mở, không phải phần frontend bước 3 và không được mô phỏng ở local.
