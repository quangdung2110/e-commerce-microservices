# Bước 4 — Duyệt sản phẩm

Ngày triển khai: 30/09/2026. Bước 3 (Inventory admin) được hoãn theo yêu cầu;
frontend sử dụng availability advisory của BFF, không tự tạo số tồn.

Ghi chú hiện trạng: phần giỏ hàng còn thiếu tại thời điểm bước 4 đã được hoàn thành
trong [bước 5](./step-5-verification.md). Các kết quả bên dưới là báo cáo của bước 4.

## Phạm vi đã triển khai

- `/`: danh mục, Product mới và đang khuyến mãi từ Search v2. Các section degrade độc lập.
- `/search`: tìm kiếm, giá nguyên VND, tình trạng hàng, khuyến mãi, sort, facet và tải thêm.
- `/c/[slug]`: danh mục từ cây `children`, facet bootstrap qua category ID.
- `/products/[slug]`: Product Detail BFF theo slug chính xác, breadcrumb, giá, gallery
  IMAGE/VIDEO, chọn SKU qua scalar variant axes, mô tả, thông số và review preview.
- `/products/[slug]/reviews`: list APPROVED, aggregate, lọc sao/SKU/có media,
  signed image/video, phản hồi cửa hàng và báo cáo review sau đăng nhập.
- Header: suggestions v2 debounce 250 ms, Enter/Arrow/Escape, chọn gợi ý làm query
  tìm kiếm (không giả link product/category), navigation theo cây danh mục thật.
- Nút thêm vào giỏ trên PDP và số lượng header dùng Cart v1 chuẩn.
  Màn quản lý giỏ hàng cũ sẽ được thay ở bước 5.

## Quy tắc contract và render

Dữ liệu đầu tiên render phía server theo ADR-002; client chỉ xử lý bộ lọc, chọn biến thể,
tải thêm và thao tác cần danh tính. Module `storefront-server.ts` chỉ import type từ
các API trình duyệt, không import runtime client axios.

> **Đã thay thế:** Search nay trả mỗi hit là một Product (đại diện bởi SKU giá thấp nhất), UI dùng
> `total_products`; xem [product-card-search.md](./product-card-search.md). Đoạn dưới là lịch sử bước 4.

Search trả mỗi hit là một SKU; UI dùng `total_skus` và ghi rõ “biến thể”, không đếm
như Product. Card dùng `product_slug` và giữ `sku_id` trong link PDP. Giá/tồn Search
là projection eventual-consistent; PDP không dựng dữ liệu giao dịch từ Search.

Facet token được giữ nguyên và gửi lặp `filter=...`; UI không tự ghép token. Count
facet là DISTINCT_PRODUCT xấp xỉ, count category là projection cập nhật theo job.
URL chứa các bộ lọc; cursor chỉ nằm trong query cache. Mỗi lượt server render có
query instance riêng để đổi filter, Back và tải lại luôn bắt đầu từ trang đầu.

Search mặc định `in_stock=true` theo contract; chọn “Tất cả” gửi `false`. Trang chủ
lấy cả SKU chưa có stock projection để vẫn duyệt được catalog khi Inventory chưa
hoàn thành. Không có dữ liệu tồn giả.

PDP chỉ resolve SKU có đủ mọi trục; tổ hợp không tồn tại được giải thích trên màn hình.
Media SKU được ưu tiên và fallback gallery Product. `UNKNOWN` vẫn cho thêm giỏ;
`OUT_OF_STOCK` bị tắt. Cart chỉ nhận `{sku_id, delta}` với `Idempotency-Key`;
lỗi retryable giữ nguyên key và payload, khóa SKU/số lượng cho tới khi thử lại xong.

Category tree cache 60 giây. Search, facet, PDP và Review đọc `no-store`; không ép
ISR lên PDP khi BFF trả degrade/no-store. Review signed URL không qua Next image
optimizer, không giữ shared-cache; mở lại trang hoặc nút “Tải mới ảnh/video”
lấy URL mới, window focus cũng làm mới danh sách.

## Sửa backend và đồng bộ

1. Gateway root Compose bỏ Search v1, expose bốn GET v2 công khai. Reindex dùng
   router riêng với `authenticated@file`, Search kiểm tra `ADMIN`.
2. Review GET public list/aggregate có router anonymous chỉ strip identity giả.
   Buyer/admin Review và Cart chuyển sang `authenticated@file`; gateway không retry write.
3. Review storage tách `REVIEW_S3_PUBLIC_ENDPOINT` cho presigner khỏi
   `REVIEW_S3_ENDPOINT` cho S3 client. Local ký host `localhost:9000`, HEAD/DELETE
   vẫn dùng `minio:9000`. Giữ URL đã ký nguyên văn; thay host làm hỏng SigV4.

Các thay đổi được phản ánh trong OpenAPI Search/Cart/Review, type được sinh lại,
gateway runtime-contract/routing matrix/implementation plan, Review
interfaces/development/operations và tài liệu frontend. Không đổi event schema hay
payload giao dịch.

## Kiểm chứng

- Typecheck: qua trong Node 20 Docker.
- ESLint: 0 lỗi; 11 cảnh báo còn lại thuộc code cũ ngoài storefront mới.
- Vitest: 62 test qua, gồm serialization facet, contract add/suggestions/reviews,
  resolve SKU, gallery fallback, UNKNOWN và no-store/identity của server reads.
- Production build: Next.js 16.2.9 qua; cả năm route bước 4 được tạo.
- Review: toàn bộ 44 test qua khi mount cả repository để đọc contracts/docs;
  gồm 2 test StorageConfig cho public signing origin và fallback endpoint.
- `docker compose config --quiet` và `git diff --check`: qua.

### Live API và UI

| Kiểm tra | Kết quả |
|---|---|
| Catalog/Search | Phát lại outbox catalog đã có, giữ 4 SKU ACTIVE; DRAFT/HIDDEN không xuất hiện. Không xóa index hay tạo stock giả |
| Cursor | `size=2` trả 2 + 2 SKU, cursor opaque từ backend, 4 ID không trùng |
| Identity gateway | Cart/reindex anonymous 401; buyer đọc reindex 403; Review list/aggregate anonymous 200; Search v1 404 |
| PDP | Catalog ACTIVE 200, DRAFT 404; `UNKNOWN` hiển thị lời giải thích và cho thêm giỏ |
| Degradation | Dừng Review local: BFF vẫn trả Product và `review_section.status=UNAVAILABLE`; UI giữ thông tin sản phẩm và trạng thái gián đoạn |
| Cart | Thêm từ UI thành công, header cập nhật 1; replay cùng key và payload giữ quantity 1. Đã xóa phần thêm QA |
| Review media | Fixture eligibility local; upload/create/reply qua API thật. Signed PUT/GET browser origin `localhost:9000` đều 200, ảnh tải được trong UI |
| Review report | UI hiện đúng lỗi báo cáo đánh giá của chính mình; tài khoản khác gửi report nhận 202 không body |
| Search UI | Enter tìm kiếm, đổi `in_stock=false` và `price_asc` giữ URL, kết quả giá tăng dần |
| Responsive | Đo viewport CSS thực tế 375/768/1280 px: không tràn ngang sau khi sửa header và ô lọc biến thể; kiểm tra sáng/tối |
| 404 và lỗi | Production build hiển thị trang không tìm thấy cho DRAFT; lỗi upstream giữ câu tiếng Việt, trace ID và nút thử lại |

Fixture Review, eligibility, reply, report, aggregate và outbox QA đã được dọn bằng
transaction có kiểm tra ID/nội dung, không tạo legal takedown. Media private được
chuyển `DELETE_PENDING` cho lifecycle worker; catalog mẫu có sẵn được giữ nguyên.
Bản preview production chạy tại `http://localhost:3000`.

### Giới hạn kiểm chứng và bước tiếp theo

- Inventory chưa hoàn thành: Search mặc định chỉ còn hàng có thể rỗng; chọn “Tất cả”
  để xem catalog. Suggestions cũng có thể rỗng khi chưa có candidate còn hàng.
  Debounce và endpoint đã kiểm tra; danh sách gợi ý có dữ liệu, facet nhiều option,
  video và tổ hợp nhiều SKU cần fixture bổ sung trong E2E bước 13. Logic scalar/SKU
  và opaque token đã có unit test.
- Trang Cart đầy đủ còn dùng code cũ và được thay ở bước 5. Bước 4 chỉ nối thao tác
  thêm giỏ trên PDP và counter header.

## Tiêu chí hoàn thành

- [x] Năm route bước 4 gọi đúng Search v2, BFF và Review public.
- [x] Server render dữ liệu đầu tiên; no-store theo contract và cache category 60 giây.
- [x] Cursor dùng helper chung, reset theo filter; delta write dùng command/idempotency.
- [x] Loading, empty, error, partial và success có nội dung tiếng Việt.
- [x] Các sửa backend được phản ánh trong docs, contracts và type sinh lại.
- [x] Typecheck, lint, test, build, live API và UI đã kiểm chứng.

## Cập nhật 2026-09-30: Product cards

Kết quả 4 SKU ở bảng kiểm chứng cũ phía trên là lịch sử. Hiện tại xem [product-card-search.md](./product-card-search.md): một Product một card, total_products, signed PIT cursor, giá Từ theo minimum eligible SKU. Seed có 28 Product/84 SKU; API trả 28 card và tai nghe 3 card. Kiểm thử gồm frontend representative link/expired cursor recovery, backend minimum/filters/multiple composite pages/snapshot mutation/PIT expiry.

Kiểm chứng bản mới: 85 unit test backend passed (12 test khác skipped), 22 API integration test passed với Elasticsearch/Kafka thật; frontend 19 test passed, typecheck/lint/build passed. Local API: cả 5 sort đi hết 28 Product qua 4 trang size=7, không trùng Product và đại diện khớp minimum SKU. HTML: search trang đầu 20 card, “tai nghe” 3 card, Gaming 3 card. Browser: “Tải thêm” từ 20 lên đủ 28 card và hết nút; card bàn phím mở PDP giá 1490000 với Red switch/Đen được chọn, tồn kho chưa xác định. Search Service và frontend production preview đã được khởi động bằng bản mới.
