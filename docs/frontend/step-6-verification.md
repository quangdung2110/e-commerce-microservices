# Frontend Step 6 Verification — Checkout và Stripe

> **Cập nhật live 11/10/2026:** xem [báo cáo bổ sung](./live-local-2026-10-11.md) và checkbox hiện hành trong implementation-plan. Các kết quả bên dưới là lịch sử kiểm chứng; blocker Stripe/CDC và một số mutation đã được kiểm tra lại.

Ngày kiểm chứng: **07/10/2026**. Phạm vi: checkout **không voucher**; voucher ở bước 11.

> Các mục dưới đây ghi lần kiểm chứng frontend ban đầu. Backend đã được sửa tiếp
> trong cùng ngày; xem phần **Cập nhật backend readiness** cuối tài liệu và
> [runbook hiện tại](./checkout-local-runbook.md). Không dùng danh sách blocker cũ
> để suy ra trạng thái runtime sau sửa.

## Luồng đã triển khai

- `/checkout` nằm dưới `RequireAuth`, client render theo ADR-002; chọn địa chỉ mặc định
  hoặc địa chỉ khác từ User. Không có địa chỉ thì dẫn tới sổ địa chỉ.
- Preview lần đầu/fresh không có `previous_checkout_token`. Đổi địa chỉ gửi token
  báo giá hiện hành. `useCommand` giữ nguyên body/key khi phản hồi chưa rõ và khóa
  đổi địa chỉ/tạo ý định khác; `Retry-After` khóa nút retry.
- Danh sách hàng, giá niêm yết, giá bán và tổng tiền lấy nguyên từ Checkout, dùng VND
  nguyên đồng. Quote chưa giữ hàng, hết hạn thì phải tải lại và xác nhận lại.
- Submit chỉ gửi `{checkout_token}`; không tạo `Idempotency-Key` mới, không gửi giá,
  số lượng, user hay payment method từ client.
- Trước Submit, URL được thay bằng `/checkout?submission=<token>`. Refresh phục hồi
  bằng GET; không giữ quote/địa chỉ/client secret trong localStorage/sessionStorage.
  URL chỉ mang token, backend vẫn kiểm tra ownership theo user đăng nhập.
- `202 PREPARING/RESULT_UNKNOWN`, mất phản hồi Submit, `409 VIEW_SUBMISSION` dẫn tới
  đọc trạng thái cùng token. GET tôn trọng `Retry-After`, dừng khi terminal/unmount
  hoặc sau 90 giây. Hết thời gian chỉ cho tiếp tục kiểm tra, không suy ra đơn thất bại.
- `SUBMISSION_NOT_FOUND` cho gửi lại **cùng token**; terminal conflict có action
  `START_NEW_PREVIEW` cho xem báo giá mới trước khi đặt lại. `EDIT_CART` dẫn về giỏ;
  `CHANGE_ADDRESS` yêu cầu chọn/sửa địa chỉ; `CHANGE_VOUCHER` cho báo giá không voucher.
- `usable=true` và đủ credentials chuyển tới payment attempt hiện có ở bước 7;
  `usable=false` chuyển chi tiết đơn. Trang Stripe đọc lại client data từ Order;
  return URL đọc Order, không coi kết quả Stripe phía trình duyệt là đơn đã trả tiền.
- Nút Thanh toán ở giỏ đã mở; khóa khi giỏ stale/đang cập nhật/kết quả lệnh chưa rõ.
  Cart DEGRADED vẫn có thể tới Checkout để backend xác minh lại toàn bộ dữ liệu.

## Đối chiếu backend

| Service | Bằng chứng | Kết quả |
|---|---|---|
| Checkout | `CheckoutController`, `RequestParser`, `PublicDtos`, `SubmissionService`, OpenAPI v1 | Đủ Preview/Submit/status; status 200/202, token-owned idempotency, action trong problem |
| User | public Address DTO/router và internal checkout snapshot trên 8083 | Có API địa chỉ cần thiết; build local hiện bị chặn |
| Cart | public `/carts/mine`; internal checkout snapshot; Product gRPC | Có API cần thiết; Compose hiện bị xung đột host port |
| Order | `InternalController`, `PaymentClientDataReader`, `OrderQueryService` | Có submission recovery và payment attempt read cho UI đã có |
| Payment | `PaymentServiceApplication`, Stripe adapters, webhook ingress/processor, Gate 3 | Runtime đã hiện thực; README module cũ sai; Sandbox E2E chưa có bằng chứng |
| Gateway | bốn router checkout trong Compose, `authenticated@file`, rate limit | Identity đúng cấu hình; không tự bịa endpoint hoặc identity test cho frontend |

## Blocker và chênh lệch cần đồng bộ

1. **User Service — blocker tái hiện được.** `docker compose build user-service`
   thất bại tại `go build`: thiếu go.sum entry cho `golang.org/x/crypto/sha3`, `md4`,
   `pbkdf2`. `go.sum` có v0.51.0 nhưng module graph hiện chọn dependency chưa đủ
   checksum; cần đồng bộ `go.mod`/`go.sum` bằng toolchain được service hỗ trợ, chạy
   test và build Docker. Không thể gọi địa chỉ/snapshot thật trước khi xử lý.
2. **Cart/Promotion — blocker Compose tái hiện được.** Cart publish `8085:8080`,
   Promotion publish `8085:8085`. Khi Promotion đang chạy, `docker compose up -d
   --no-deps --no-build ... cart-service ...` báo `Bind for 0.0.0.0:8085 failed:
   port is already allocated`. Chọn cổng host riêng cho Cart hoặc bỏ publish host
   (frontend dùng gateway); đồng bộ README/runbook. Cổng nội bộ service không cần đổi.
3. **Payment — tài liệu module sai.** `java/services/payment-service/README.md`
   vẫn ghi “Gate 2 implementation foundation”, “chưa phải runtime Spring Boot”.
   Code và `docs/payment/README.md`, `implementation-gates.md` đã mô tả runtime
   Gate 3. Cần viết lại README module theo trạng thái hiện tại và hướng dẫn runtime.
4. **Stripe Sandbox E2E — điều kiện môi trường chưa đáp ứng.** Compose mặc định
   đặt `sk_test_local_change_me`, `pk_test_local_change_me`, `whsec_local_change_me`.
   Gate 3 cũng ghi Stripe Sandbox E2E `NOT RUN`. Cần ba biến
   `PAYMENT_STRIPE_SECRET_KEY`, `PAYMENT_STRIPE_PUBLISHABLE_KEY`,
   `PAYMENT_STRIPE_WEBHOOK_SECRETS` hợp lệ và forward webhook tới
   `/v1/payments/webhook/stripe`. Không in secret vào báo cáo, không giả success.
5. **Checkout — metadata tài liệu cần rà soát.** README/interfaces còn frontmatter
   `scope: target`, `status: proposed`, trong khi bảng service status đã ghi
   `implemented`. Nội dung API khớp controller; metadata này không chặn frontend.

Không sửa backend/contract hoặc dependency graph trong bước frontend này; các vấn đề
trên được ghi để service owner đồng bộ code, runtime và tài liệu cùng nhau.

## Kiểm chứng

Test mới bao phủ Preview StrictMode, retry cùng key/body, khóa sửa lúc mất phản hồi,
đổi địa chỉ với previous token, quote hết hạn, action conflict, recovery sau reload,
Submit mất phản hồi/usable false, URL không chứa secret, status URL allowlist và
cancellation khi unmount. Regression giỏ đã đổi từ “chờ bước 6” sang mở/khóa checkout.

| Kiểm tra | Kết quả |
|---|---|
| TypeScript | `npm run typecheck` qua; production build cũng hoàn tất TypeScript |
| ESLint | 0 lỗi, 3 cảnh báo có sẵn ở `seed_search_data.mjs`/`AddressForm.tsx` |
| Regression | 30 file / 233 test qua, gồm 18 test UI Checkout và 5 test client/recovery helper mới |
| Production | Next.js 16.2.9 build qua, `/checkout` là dynamic route |
| Smoke UI | Dev `/checkout` trả HTTP 200; phiên đăng nhập có sẵn render được heading và lỗi API địa chỉ (User chưa chạy); viewport 706 px dark không tràn ngang |
| Terra | Token scan không có màu trắng/đen trực tiếp, gray hard-code, shadow/gradient cấm trong code mới |

Live end-to-end chưa đạt do các blocker trên; không đánh dấu bước 6 mua đơn thật là xong.
Các dependency khởi động riêng cho kiểm chứng (Inventory, checkout-redis) đã được
dừng lại sau khi xác định blocker, giữ các service vốn đang chạy.

Lưu ý công cụ: sau khi chạy build và smoke dev trên cùng bind mount, `.next/dev/types`
có file sinh bị ghi chồng, làm một lượt typecheck lỗi syntax trong file sinh. Đã tạm
dừng dev, xoá đúng cache type sinh này, chạy `next typegen`, rồi kiểm tra lại trước
khi khôi phục dev server. Không sửa generated OpenAPI hoặc source để né lỗi.

## Gate live còn lại

- Build/start User và Cart, Checkout, Temporal/Order/Payment và CDC outbox theo runbook.
- Buyer fixture riêng có địa chỉ, SKU ACTIVE và stock; Preview/replay/đổi địa chỉ,
  submit với quote cũ/hết hạn, refresh khi submission đang chạy.
- Stripe test success/3DS/decline; webhook → Payment outbox → Order; Order PAID,
  giỏ được dọn qua `ORDER_PAID`, thanh toán lại khi attempt thất bại.
- Visual QA 375/768/1280 px × light/dark, focus/bàn phím/screen reader cho trạng thái
  success/pending/error/empty và Stripe Element. Chưa có đủ bằng chứng live cho ma trận này.

## Cập nhật backend readiness — 07/10/2026

Đã sửa trong repo:

- User `go.mod`/`go.sum` đồng bộ bằng Go 1.25 với module graph hiện tại, bổ sung
  `golang.org/x/crypto v0.50.0` indirect; không nâng các dependency trực tiếp khác.
- Root Compose có `user-migrate` versioned, User đợi migration thành công. Root DB
  `user_service` đã có version 1, dirty=false và 4 bảng trong schema `user`.
- User Docker/standalone healthcheck dùng GET readiness; `wget --spider` cũ gửi HEAD
  bị router từ chối dù GET trả UP. Đã build lại image.
- Cart bỏ host publish 8085, giữ internal 8080; Cart và Promotion chạy đồng thời.
- Root PostgreSQL local tăng max_connections 100 → 300 sau khi toàn stack tái hiện
  cạn connection; giữ nguyên volume. Đây không phải sizing/capacity chứng nhận production.
- Có script PowerShell register/check CDC, giữ routing expression, DB riêng, reject
  FAILED task; có mẫu env và ignore root `.env.local`.
- Payment/User/Cart/Checkout docs đã cập nhật runtime, migration, env/recreate và
  phân biệt healthy/offline test với gate giao dịch Sandbox.

| Kiểm tra mới | Kết quả |
|---|---|
| User unit | `go test -race ./test/unit/...` PASS (3 package) |
| User static | `go vet ./...` PASS |
| User integration | `go test -tags=integration -count=1 -timeout=15m ./test/integration/...` PASS trên PostgreSQL/Redis Testcontainers riêng |
| Docker | User và Cart build PASS; root Compose config PASS |
| Migration | user-migrate exit 0, version 1 sạch; chạy lại không đổi schema |
| CDC helper | Test DB isolation/routing/validate/check/FAILED-task PASS |
| CDC runtime | Payment/Order/Inventory connector và mỗi task RUNNING; chưa chứng minh financial fact end-to-end |
| Docs/contract foundation | validate_docs.ps1 và validate-payment-gate2.ps1 PASS |
| Kafka recovery | Hai Auth Topic ID phục hồi; recreate ZooKeeper giữ metadata; restore script replay no-op và guard broker-running từ chối |

**Lỗi Kafka đã phát hiện và phục hồi:** `t.auth.events` broker Topic ID ban đầu là
`nkeMGxOxTjaF9hnm8rgzEQ`, partition.metadata trên volume là
`sTQX6KIfR9GTjRrS5YUw3g`. ListOffsets trả NOT_LEADER ở protocol 2.1/2.8/3.5;
restart broker giữ volume không sửa được. Không reset offset/topic, không thay Topic
ID log và không xóa volume. Audit tất cả topic chỉ thấy hai Auth topic không khớp;
mỗi topic có một partition/lịch sử ID nhất quán trên disk.

Đã dừng clients/broker, sao lưu toàn bộ Kafka và ZooKeeper cùng checksum vào
`.tools/kafka-recovery/20261007-restore-auth` (gitignored), chuyển dữ liệu ZooKeeper
từ anonymous sang named volumes rồi phục hồi **znode Topic ID** theo lịch sử disk.
`scripts/restore-auth-topic-ids.ps1` yêu cầu broker dừng, backup hợp lệ, assignment
single-broker không đổi và có rollback journal. Không sửa partition.metadata, không
reset offset hay tạo event giả. Metadata được giữ qua recreate ZooKeeper; script
chạy lại trả already restored, không thay đổi.

`scripts/check-auth-kafka.ps1` hiện PASS. ListOffsets protocol 2.1/2.8/3.5 đều đọc
được; sau bật consumer true, group User offset 13/log-end 13/lag 0, có 13 profile
bootstrap từ event thật và 0 địa chỉ. Kafka retention có thể dọn segment cũ khi
broker hoạt động lại; bản archive trước recovery vẫn giữ lịch sử để phục hồi.

Gate còn lại: buyer cần địa chỉ/giỏ/SKU stock hợp lệ và khóa Stripe Sandbox. E2E
success/3DS/decline, PAID/Inventory commit/Cart cleanup và full visual QA vẫn NOT RUN.
