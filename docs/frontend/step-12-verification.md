# Frontend Step 12 — Kiểm duyệt, kho đầy đủ và hệ thống

Ngày cập nhật: **11/10/2026**.

## Phạm vi

| Route | Chức năng |
|---|---|
| `/admin/reviews?status=` | Queue `REPORTED` / `HIDDEN_BY_REPORT`, cursor; panel nội dung, signed media và báo cáo; dismissal, official reply, legal takedown. |
| `/admin/inventory/operations?view=` | Danh sách kho, tồn, movement; queue xuất kho, kiểm định và cách ly. Bộ lọc trên URL, bảng desktop/card mobile, cursor trước/sau. |
| `/admin/inventory/returns?order_id=` | Tìm Refund Scope theo đơn; có link từ chi tiết đơn admin. |
| `/admin/inventory/refund-scopes/[refundScopeId]` | Counter từng dòng, nhận vào cách ly theo SKU/dòng đơn cố định, giới hạn số lượng còn thiếu. |
| Form trên queue | Kiểm định một phần PASS/FAIL; HOLD, DISPOSE, RETURN_TO_SUPPLIER; đảo disposition theo ID/dòng và sequence. |
| `/admin/system` | Lối vào công cụ hệ thống. |
| `/admin/system/search-reindex?status=&run_id=` | 50 lượt gần nhất, tra ID, tạo full reindex, tiến độ, huỷ, rollback. |
| `/admin/system/account-deletion` | Account UUID, gõ lại chính xác mã tài khoản trước khi gửi. |

Tất cả dùng layout `RequireRole ADMIN`, HTTP client chung và type sinh từ OpenAPI.
Không cần sửa backend hoặc contract cho bước này.

## Đối chiếu backend

- Inventory `web/AdminController.java`, `web/dto/AdminDtos.java`, `service/ReturnService.java`:
  query và lệnh đã có runtime. Chỉ `REGISTERED`/`IN_PROGRESS` nhận hàng; FAIL không
  chuyển lại PASS; HOLD không giảm lượng có thể xử lý; reversal chỉ trả vào quarantine.
- Review `controller/AdminModerationController.java`, `service/AdminReplyService.java`:
  queue là surface đọc duy nhất của admin; reply chỉ cho `APPROVED`; dismissal gỡ mọi
  active report và khôi phục review ẩn. Signed media có TTL ngắn; ảnh dùng
  `Image unoptimized`, hết hạn phải tải lại hồ sơ.
- Search `controller/api/ReindexRunController.java`: create/list/get/cancel/rollback
  đã có. `CUTOVER` không được huỷ; backend kiểm tra rollback là cutover mới nhất và
  index cũ còn tồn tại.
- Auth `api/handler/account_handler.go`, `domain/service/account_deletion_service.go`:
  deletion không có body, lý do `ADMIN_DELETION`; `202` trả `status=DELETED` và
  `hard_delete_after`. Tài khoản đã xoá không được gửi lại.
- Gateway `docker-compose.yml`: các route này dùng `authenticated@file`.

## Quy tắc lệnh

- Inventory gửi command/correlation/causation. Form giữ payload và key khi retry, kể
  cả HTTP 500; hiện mã lệnh để tra durable outcome. Nút khoá theo `Retry-After`, dialog
  không đóng khi đang gửi. Nếu đóng/tải lại trang thì cần tra Kết quả lệnh trước khi
  tạo ý định mới; bộ nhớ form không thay thế durable outcome.
- Takedown và xoá account không tự retry lỗi mạng/5xx. Takedown phải đọc lại queue;
  account deletion bị khoá vì không có API đọc phục hồi. Không thử xoá tài khoản thật.
- Dismissal idempotent theo outcome, reply là PUT upsert; không bịa idempotency key,
  actor hoặc body lý do cho dismissal.
- Reindex không tự gửi lại create; phải đọc lại list/detail sau kết quả mơ hồ. Poll
  dừng ở trạng thái cuối, lỗi đọc hoặc unmount, có nút tải lại. URL giữ `run_id`.
- Lỗi dùng `code` → tiếng Việt, không lộ `detail` hoặc `failure.message` của Search.
  Form giới hạn độ dài/số lượng, yêu cầu bằng chứng và xác nhận thao tác thực tế.

## Kiểm chứng

- Kiểm thử riêng và hồi quy `useCommand`: **19/19 qua** trước vòng kiểm chứng toàn bộ.
  Có tests cho HTTP 500 giữ payload/key, exact account confirmation, chặn retry xoá
  chưa rõ, review ẩn không được reply, dismissal theo review ID, API/header/encoding,
  PASS/FAIL, HOLD/supplier return và trạng thái cuối Reindex.
- Live read qua gateway với admin dev: danh sách kho trả **200**, có hai kho.
  Review queue và Search reindex list trả **404** ở stack local tại thời điểm kiểm tra.
  Các mutation bước 12 chưa được chứng nhận live.
- Production build (`next build --webpack`) qua, gồm tất cả route bước 12.
- Toàn bộ suite sau sửa điều hướng: **35 file / 266 test qua**.
  Lint **0 lỗi / 3 cảnh báo có sẵn** (`seed_search_data.mjs`, `AddressForm.tsx`).
  `npm run typecheck` qua sau vòng kiểm tra cuối, bao gồm source và test.
  `git diff --check` không có lỗi whitespace.
- Visual QA một phần bằng trình duyệt thật: Vận hành kho desktop 1280 px,
  form reversal và trang xoá account mobile 375 px, trang kiểm duyệt 768 px
  (dark mode). Đã phát hiện và sửa sidebar chọn đồng thời hai mục kho.
  Trạng thái lỗi gateway hiện đúng thông báo tiếng Việt và nút tải lại.
  Chưa chứng nhận toàn bộ ma trận light/dark, trạng thái có dữ liệu và a11y.

## Giới hạn còn mở

- Inventory MFA vẫn là gate production của Auth/gateway, không mô phỏng ở local.
- Không có disposition list/history API: reversal nhập ID/sequence từ chứng từ;
  server kiểm tra lượng tối đa và sequence liên tiếp.
- Admin queue không trả official reply cũ: form nói rõ PUT thay thế reply hiện có.
- Chưa chứng nhận live chuỗi nhận hàng → PASS/FAIL → disposition/reversal,
  takedown, reindex/cancel/rollback và xoá account; cần stack cùng fixture local riêng.
