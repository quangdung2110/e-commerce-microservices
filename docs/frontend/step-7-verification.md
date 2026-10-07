# Frontend Step 7 Verification — Buyer Orders

Ngày cập nhật: **05/10/2026**.

## Phạm vi đã triển khai

- Danh sách `/orders`: sáu Buyer tab cộng “Tất cả”, tìm theo tên sản phẩm snapshot,
  cursor “Tải thêm”, tổng số đơn và đủ loading/empty/error/stale-success.
- Chi tiết `/orders/{id}`: kiện theo kho, dòng hàng, fulfillment, địa chỉ snapshot,
  tổng tiền/giảm giá/thành tiền và các hành động từ `available_actions`.
- Huỷ pre-payment: `USER_CANCEL`, idempotency key và poll Buyer operation.
- Trả hàng: proper subset, mỗi dòng luôn gửi full ordered quantity, reason/details,
  guard một lần và điều hướng về refund progress.
- Refund case: trạng thái tài chính tách khỏi Inventory scope finalization; trạng thái
  cần kiểm tra thủ công không bị diễn đạt thành thất bại cuối.
- Thanh toán lại: tạo asynchronous payment attempt, lấy result reference, đọc client
  data và mount Stripe Payment Element. `return_url` poll Order; client không tự đánh
  dấu đơn là đã thanh toán.

## Contract và backend đã đối chiếu

- `contracts/http/order/v1/openapi.yaml` và type `generated/order.ts` được sinh lại.
- `OrderController`, `OrderCommandService` và `OrderQueryService` hiện có đủ endpoint
  list/detail/cancel/return/refund/payment-attempt/operation.
- Payment attempt create hiện trả `202 OperationAccepted`; code cũ từng giả định
  `200/201 PaymentAttempt` đã được loại bỏ.
- Tiền là VND integer; ngày hiển thị theo `Asia/Ho_Chi_Minh`; lỗi dùng stable problem
  code, không hiển thị backend `detail`.

## Bằng chứng offline

| Gate | Kết quả |
|---|---|
| Sinh OpenAPI type | 12/12 contract thành công |
| TypeScript | `tsc --noEmit` qua |
| Unit/component tests | 18 file, 110 test qua; gồm 9 test Order mới |
| ESLint | 0 lỗi; 3 cảnh báo có sẵn ngoài code Order |
| Production build | Next.js 16.2.9 build qua; 5 route Order là dynamic |
| Terra token scan | Không có màu hard-code, màu trắng/đen trực tiếp hoặc shadow cấm |

## Chưa kiểm chứng live

Máy chạy phiên này không có Docker CLI nên chưa thể dựng gateway + Auth + Order +
Payment, tạo order fixture và chạy Stripe test mode/Stripe CLI. Cần kiểm tra tiếp:

1. Buyer chỉ thấy đơn của chính mình; non-owner detail là 404.
2. Huỷ từ PENDING_PAYMENT đi qua CANCELLING tới CANCELLED.
3. Payment attempt FAILED tạo được attempt kế tiếp; thẻ test success/3DS/decline;
   webhook cập nhật Order và trang complete dừng polling.
4. COMPLETED + mọi line DELIVERED gửi return proper subset đúng một lần; refund case
   đi qua Inventory inspection/finalization và Payment refund.
5. Visual QA 375/768/1280 px, light/dark, bàn phím và screen reader.
