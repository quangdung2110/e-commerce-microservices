# Bước 5 — Giỏ hàng Cart v1

Ngày triển khai: 30/09/2026. Bước 3 tiếp tục hoãn; Cart chỉ kiểm catalog saleability,
không đọc hoặc giả lập tồn kho Inventory.

## Phạm vi

- `/cart` trong nhóm protected, CSR theo ADR-002, skeleton trong lúc refresh phiên.
- Danh sách SKU phẳng, tên/thuộc tính biến thể, ảnh có fallback, giá hiện tại/giá lúc
  thêm, quantity 1–100 và thành tiền.
- Tăng/giảm gửi delta +1/-1. Ở quantity 1 nút giảm disabled, xóa dùng DELETE.
- Chọn từng dòng/chọn tất cả/xóa đã chọn; xóa đơn và batch đều gửi JSON `{sku_ids}`
  tại `/api/v1/carts/mine/items` với `Idempotency-Key`.
- Tóm tắt dùng `total_units` và `total_unique_skus` thật. Checkout disabled kèm lời
  giải thích tới bước 6.
- Header, PDP và Cart dùng một API module/type sinh từ OpenAPI và một cache theo
  account. Bỏ các endpoint cũ, client riêng cho PDP và bản sao server state trong
  `useCartStore`.

## Quy tắc dữ liệu và retry

Money giữ `amount_minor`/`currency`; VND không chia 100. Tạm tính chỉ cộng SKU còn
bán có giá CURRENT, cùng tiền tệ và số nguyên an toàn. Giá null, dữ liệu DEGRADED
hoặc lỗi đọc mới nhất hiện “Chưa tính được”, không thay bằng giá lịch sử/0.

`unit_price_at_add` luôn là giá lịch sử. Cảnh báo giá chỉ so số tiền cùng currency
khi CURRENT và SKU còn bán; ghi chiều tăng/giảm và hai mức giá. `is_available=false`
ở CURRENT hiện “Ngưng bán”, loại khỏi tạm tính. Ở DEGRADED, UI ghi chưa xác minh
tình trạng bán, không gắn nhãn tồn kho từ snapshot.

Mỗi ý định dùng một UUID. Lỗi mạng/429/503 hoặc `retryable=true` giữ nguyên payload
và key; các thao tác khác bị khóa. Retry tuân thủ số giây từ `Retry-After`. Sau write
đọc lại backend, không thay quantity/xóa dòng trước khi server xác nhận. Một GET
thành công sau timeout không chứng minh kết quả write, kể cả khi giỏ đã trống; nút
retry vẫn replay DELETE cũ để lấy kết quả xác định.

Lỗi 422 xác định cho phép ý định mới với key mới sau refetch. Read lỗi sau khi có
data vẫn giữ nội dung cũ, cảnh báo stale và khóa chỉnh sửa tới khi đọc lại. Không
hiển thị `detail` thô; mã Cart bổ sung có câu tiếng Việt. Query và state lựa chọn/lệnh
của màn giỏ cách ly theo account ID; AuthProvider dọn cache khi kết thúc/đổi phiên.

## Đồng bộ tài liệu và backend

Đã đối chiếu OpenAPI Cart với router, request validator, response DTO, CartService,
Product adapter và Lua. Không cần sửa backend hoặc contracts cho bước 5. Các type
Cart đã sinh ở bước 0 vẫn khớp; không chỉnh tay type sinh. Tài liệu frontend
(README, sitemap, kế hoạch, tech-stack) và `docs/cart/interfaces.md` phản ánh client
mới. `docs/cart/domain.md` bổ sung hai mã `QUANTITY_INVALID`/`ITEM_NOT_FOUND` đã có
trong backend và sửa tên command thành điều chỉnh theo delta; không đổi hành vi API.

## Kiểm chứng

| Kiểm tra | Kết quả |
|---|---|
| TypeScript | `npm run typecheck` qua trong Node 20 Docker |
| ESLint | 0 lỗi; 3 cảnh báo ngoài Cart (`seed_search_data.mjs`, `AddressForm.tsx`) |
| Vitest | 86 test qua; 24 test mới cho Cart API, tiền/giá, UI, recovery, retry và cách ly account |
| Production build | Next.js 16.2.9 build thành công, `/cart` được sinh dưới protected layout |
| Terra | Token semantic, layout sáng/tối, skeleton/empty/error/partial/success, label/aria, control quantity/xóa 44 px |
| Responsive UI thật | Đo `innerWidth` CSS 375/768/1280 px, scrollWidth không vượt viewport; mobile một cột, desktop có summary bên phải |
| Anonymous | `/cart` chuyển tới `/login?next=%2Fcart`; không gọi private query trước khi Auth ready |
| UI thật | Đăng nhập quay về Cart, PDP thêm SKU, tăng/giảm cập nhật dòng + header + tạm tính, F5 giữ phiên và snapshot; chọn một phần/chọn tất cả/xóa hai dòng, Tab tới nút xóa |
| Degrade thật | Product gRPC timeout tự nhiên sau build: Cart giữ hai dòng, báo chưa xác minh giá/tình trạng, tạm tính chưa biết; tải lại phục hồi CURRENT và 31.480.000 ₫ |
| Smoke API thật | 6 nhóm kiểm tra: replay POST, 409 key/payload, delta âm/422, clamp 100, shape Cart/Money, DELETE batch/replay |

Smoke test có tại [`verify-cart-step5.mjs`](../../frontend/scripts/verify-cart-step5.mjs).
Chạy trong container Node 20 nối `ecommerce-network`, working directory
`/repo/frontend`: `node scripts/verify-cart-step5.mjs`. Mặc định dùng tài khoản buyer
local của `create-dev-account.sh`; chỉ chạy khi giỏ test trống, chỉ xóa SKU QA trong
`finally`. Biến `CART_QA_BASE`, `DEV_EMAIL`, `DEV_PASSWORD` cho cấu hình local khác;
không trỏ tới production. Lần chạy đầu gặp Product gRPC deadline 200 ms khi nguội;
chạy lại sau đó qua đủ 6 nhóm, không đổi timeout/backend. SKU thêm qua API và UI QA
đã được xóa, giỏ buyer local quay về trạng thái trống ban đầu; catalog có sẵn giữ nguyên.

Ảnh kiểm chứng: [desktop](./assets/step-5-cart-desktop.jpg),
[mobile](./assets/step-5-cart-mobile.jpg),
[degraded](./assets/step-5-cart-degraded.jpg),
[giỏ trống sau dọn QA](./assets/step-5-cart-empty.jpg).

## Giới hạn và bước tiếp theo

- Giá đổi, null/degrade, response bị mất, retry 429 và tài khoản khác đã được kiểm tra
  bằng fixture Vitest; degrade còn quan sát được với Product timeout thật. Không dừng
  Product/Redis hoặc sửa giá catalog trên stack đang chạy chỉ để tạo lỗi. E2E fault
  injection mở rộng thuộc bước 13.
- Inventory chưa hoàn thiện; Cart không xác nhận số tồn. Checkout bước 6 sẽ kiểm
  giá, saleability và allocation trước đặt hàng.
- Idempotency intent được giữ trong bộ nhớ của component trong phiên thao tác,
  không persist key qua F5 hoặc tab khác. Backend vẫn bảo vệ replay cùng key.
- Checkout và lựa chọn chỉ thanh toán một phần giỏ không được triển khai ở bước 5;
  checkbox tại Cart chỉ dành cho xóa nhiều dòng.
