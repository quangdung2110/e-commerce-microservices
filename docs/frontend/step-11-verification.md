# Bước 11 — Voucher trong checkout

> **Cập nhật live 11/10/2026:** xem [báo cáo bổ sung](./live-local-2026-10-11.md) và checkbox hiện hành trong implementation-plan. Các kết quả bên dưới là lịch sử kiểm chứng; blocker Stripe/CDC và một số mutation đã được kiểm tra lại.

Ngày **10/10/2026**. Kế hoạch ở [implementation-plan §13](./implementation-plan.md#13-bước-11--voucher-trong-checkout).
Contract nguồn: [Checkout v1](../../contracts/http/checkout/v1/openapi.yaml),
[Promotion v1](../../contracts/http/promotion/v1/openapi.yaml).
Quyết định: [Checkout ADR-017](../checkout/decisions/ADR-017-voucher-options-read-composition.md),
[Promotion ADR-018](../promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md).

## Phạm vi

`/checkout` có khay voucher hiển thị tên/mã, mức giảm, đơn tối thiểu, hạn dùng, nguồn
wallet/public, mức giảm dự kiến và lý do không đủ điều kiện. Một đơn dùng một voucher.
Người mua áp dụng voucher trong ví, lưu voucher công khai hoặc nhận voucher giới hạn
rồi áp dụng; có thể bỏ voucher, đổi địa chỉ hoặc làm mới báo giá.

Mã chính:

- `frontend/src/lib/api/checkout.ts`: type sinh từ contract, GET voucher options.
- `frontend/src/lib/api/promotion.ts`: save với body `{}`, claim với expected revision,
  giữ Retry-After; không gửi identity, giá, quantity hay category facts từ browser.
- `frontend/src/hooks/use-checkout-vouchers.ts`: query theo account/token, acquisition,
  bounded claim replay, retry cùng ý định và cancellation.
- `frontend/src/lib/checkout-voucher.ts`: nhãn eligibility tiếng Việt, mức giảm VND/phần trăm.
- `frontend/src/components/checkout/checkout-voucher-tray.tsx`: loading/empty/error/partial/success.
- `frontend/src/components/checkout/checkout-page-content.tsx`: nối Preview mới, giữ lựa
  chọn qua đổi địa chỉ, xử lý promotion_reason/dropped_promotion, khoá Submit khi cần.

## Quy tắc và backend đã đối chiếu

Đã đọc `VoucherOptionsService`, `PreviewService` (Checkout),
`CustomerPromotionController`, `ClaimService`, `CheckoutDtos` (Promotion) và domain/ADR.
Backend dựng context từ snapshot của token; options chỉ advisory. Public voucher cần
SAVE/CLAIM theo `required_action`; browser luôn lấy revision claim từ item trong khay.

Save/claim replay theo natural key user/promotion; không thêm Idempotency-Key hay
ETag quản trị. `202 CLAIM_PENDING` replay cùng POST (không có GET claim status).
Polling tôn trọng Retry-After và dừng sau 30 giây; nếu khoảng chờ vượt deadline thì
không POST sớm. UI vẫn giữ yêu cầu và thời điểm retry, kể cả báo giá đã hết hạn.
Unmount huỷ request/polling; account mới không kế thừa state cũ.

Chọn/bỏ dùng `previous_checkout_token`; bỏ gửi `promotion_id: null`. Mức giảm cuối
lấy từ Preview mới, không lấy từ save evaluation hoặc estimated_discount. Preview
retry giữ nguyên body/key. Đổi địa chỉ/làm mới giữ voucher; `CHANGE_VOUCHER` có nút
báo giá mới bỏ voucher và cần xác nhận lại. Không tự đặt đơn sau bất kỳ cập nhật nào.

Lỗi options 503/429 chỉ ảnh hưởng khay; token không hợp lệ/hết hạn/superseded khoá
Submit và cho làm mới báo giá. Token đã submit chuyển sang recovery theo status_url
đã kiểm tra. Mất phản hồi save/claim khoá thay đổi cho tới khi tiếp tục kiểm tra cùng
ý định hoặc nhận kết quả cuối. Lỗi backend chỉ hiển thị theo code, không lộ detail.

Không sửa source backend, OpenAPI, gateway hay generated types. Source hiện có đã
đáp ứng bước 11. `/vouchers` vẫn ngoài phạm vi vì chưa có API wallet độc lập.

## Kiểm chứng

| Kiểm tra | Kết quả |
|---|---|
| TypeScript `tsc --noEmit` | PASS trong `node:20-alpine` |
| Toàn bộ Vitest | PASS, 252 test / 32 file tại thời điểm chạy; workspace đang có thay đổi bước 12 song song |
| Kiểm thử bước 11 cuối | PASS, 44 test / 4 file, gồm hai ca mới về retry báo giá có voucher và recovery từ khay; đã kiểm lại định dạng phần trăm tiếng Việt |
| Lint file bước 11 | PASS, 0 lỗi/cảnh báo |
| Lint toàn repo cuối | PASS, 0 lỗi/3 cảnh báo cũ ở `seed_search_data.mjs` và `profile/AddressForm.tsx`; lỗi admin review từ thay đổi bước 12 song song đã được giải quyết trước lượt cuối |
| Production build | PASS, Next.js 16.2.9; `/checkout` là dynamic route; TypeScript và sinh trang hoàn tất |
| Luồng component | Wallet apply; public save rồi Preview; claim 202 → retry đúng revision → Preview; bỏ voucher; đổi địa chỉ/làm mới giữ voucher; giá chính thức khác ước tính |
| Nhánh lỗi | Lost save/Preview response; Retry-After; claim gate stale; ineligible; tray 503 không khoá Submit; superseded token; PROMOTION_INVALID; unmount cancellation |
| Timeout claim | Đồng hồ giả: Retry-After 60 giây > budget 30 giây; chỉ một POST, giữ intent/deadline, không áp dụng sớm |
| Auth/User local | Login admin/buyer qua gateway; tạo và verify account QA riêng `step11@technova.test`; User bootstrap từ event thật và tạo địa chỉ qua API |
| Checkout live | Chưa PASS: Cart add SKU trả 503 PRODUCT_UNAVAILABLE (gRPC Product DeadlineExceeded); gRPC GetSku gọi trực tiếp cũng timeout 5 giây |
| Promotion live | PASS qua gateway bằng account QA thật: save `{}` trả 201 → replay 200, evaluation null và không kéo dài expired_at; claim cùng revision trả 201 → replay 200, giữ cùng wallet_id |
| Visual QA | Chưa RUN: công cụ in-app browser timeout ở Page.navigate và Emulation.setFocusEmulationEnabled; không có ảnh để xác nhận responsive/light/dark |

## Giới hạn và việc còn lại

- Kiểm chứng đầy đủ với Product gRPC hoạt động, SKU còn stock và `CHECKOUT_VOUCHER_OPTIONS_ENABLED=true`:
  Preview → options → save/claim → Preview có giảm giá → Submit cùng token → Order/Stripe.
- Không chạy giao dịch Stripe hoặc tạo Order trong lần kiểm chứng này; gate Sandbox
  đầy đủ vẫn theo [checkout-local-runbook](./checkout-local-runbook.md).
- Kiểm tra trực quan 375/768/1280 px, light/dark, bàn phím và screen reader khi browser
  automation hoạt động. Các class dùng semantic token Terra và có heading/label thật.
- Account QA, địa chỉ và campaign QA được giữ trong database local; Promotion không có
  API xoá campaign/voucher. Không xoá volume, sửa dữ liệu tài khoản buyer cũ hay giả context.

## Runtime local

Image Promotion cũ (container tạo 07/10) trả 400 cho save `{}`, lệch source ADR-018.
Đã chạy `docker compose up -d --no-deps --build promotion-service`, giữ database,
và kiểm chứng lại sau khi healthy. Không thay source hay cấu hình backend. Campaign
QA đã xuất bản có ID `4`; voucher public/limited có ID `5`/`6`, đều giảm 10.000 VND.
Campaign nháp QA ID `3` cũng còn trong database từ lần chuẩn bị fixture.

Docker ban đầu không có backend chạy. Đã bật dependency local để kiểm chứng; sau khi
xác nhận blocker gRPC đã dừng Cart, Checkout, Inventory, Product và frontend dev để
giảm tải. Auth, User, Promotion, gateway/gateway-auth, Kafka/ZooKeeper, PostgreSQL,
Redis và checkout-redis vẫn chạy. Các file script QA tạm đã được xoá khỏi workspace.
