# Frontend

Ứng dụng Next.js trong [`frontend/`](../../frontend/) phục vụ cả storefront cho người mua
và trang quản trị `/admin`. Quy tắc giao diện nằm trong skill Terra
(`.claude/skills/terra-ui/`).

| Tài liệu | Nội dung |
|---|---|
| [tech-stack.md](./tech-stack.md) | Công nghệ đã chọn và lý do |
| [sitemap.md](./sitemap.md) | Toàn bộ route, API nguồn, quyền truy cập, hiện trạng và khoảng trống API |
| [voucher-wallet-2026-10-20.md](./voucher-wallet-2026-10-20.md) | B01: API và ví voucher độc lập, trạng thái/lịch sử, phân trang và kiểm thử backend thật |
| [storefront-ui-update-2026-10-17.md](./storefront-ui-update-2026-10-17.md) | Bộ lọc mobile, trung tâm trợ giúp/chính sách đối chiếu backend, trang chủ có ảnh thật và kết quả kiểm chứng |
| [storefront-live-review-2026-10-17.md](./storefront-live-review-2026-10-17.md) | Khởi động đủ 26 container; review bằng backend local: menu mobile, ảnh/QA, voucher và thao tác mua |
| [implementation-plan.md](./implementation-plan.md) | Lộ trình 14 bước; chi tiết bước 0–12 |
| [live-local-2026-10-11.md](./live-local-2026-10-11.md) | Kiểm chứng live bước 0–12: Stripe/3DS/retry/refund, Review CDC, xoá QA; lỗi Search và gate còn mở |
| [step-3-verification.md](./step-3-verification.md) | Admin Kho tối thiểu, kiểm thử offline/live và gate MFA production |
| [step-4-verification.md](./step-4-verification.md) | Search v2/PDP BFF/Review, đồng bộ backend và kiểm chứng |
| [step-5-verification.md](./step-5-verification.md) | Cart v1, delta/xóa batch, giá degrade, idempotency và kiểm chứng |
| [step-6-verification.md](./step-6-verification.md) | Checkout không voucher, Stripe, phục hồi submission và blocker backend/local |
| [checkout-local-runbook.md](./checkout-local-runbook.md) | User migration, Cart/Gateway, Stripe Sandbox, CDC và gate mua hàng end-to-end |
| [step-7-verification.md](./step-7-verification.md) | Đơn hàng Buyer, huỷ/trả/hoàn tiền và Stripe Payment Element tối thiểu |
| [step-8-verification.md](./step-8-verification.md) | Đơn hàng Admin, lệnh vòng đời và ghi nhận physical dispatch |
| [step-9-verification.md](./step-9-verification.md) | Đánh giá sau mua (media, sửa một lần), hộp thông báo, backend bổ sung và kiểm chứng live |
| [step-10-verification.md](./step-10-verification.md) | Admin Khuyến mãi: campaign, voucher, Rule AST builder, markdown, cấp voucher và kiểm chứng live |
| [step-11-verification.md](./step-11-verification.md) | Voucher trong checkout: khay qua Checkout, lưu/nhận qua Promotion, Preview mới và kiểm chứng |
| [step-12-verification.md](./step-12-verification.md) | Kiểm duyệt, operational queues, hàng trả/cách ly và công cụ hệ thống |
| [e2e-test-checklist.md](./e2e-test-checklist.md) | Danh mục 12 hành trình và 259 kịch bản E2E; cột "Kết quả đã chạy" link tới từng báo cáo, cập nhật 16/10/2026; phạm vi và giới hạn nằm trong từng ô |
| [e2e-moderation-fix-report-2026-10-14.md](./e2e-moderation-fix-report-2026-10-14.md) | Sửa F18-01: timestamp tác giả riêng, migration V4, contract/UI và retest local |
| [e2e-reviews-fix-report-2026-10-13.md](./e2e-reviews-fix-report-2026-10-13.md) | Sửa F17-01–07: immutable media, validation, snapshot, cancellation và UI callback; 270 tests + runtime/browser retest. Product timeout giữ nguyên |
| [e2e-checkout-fix-report-2026-10-15.md](./e2e-checkout-fix-report-2026-10-15.md) | Sửa F08-01/F08-02: Payment no-create proof, Order cleanup, recovery UI, contract 1.1.0; legacy 770072 cần recovery riêng |
| [decisions/](./decisions/README.md) | ADR của frontend |
| [e2e-search-fix-report-2026-10-16.md](./e2e-search-fix-report-2026-10-16.md) | Sửa ba lỗi Search: URL giá invalid, breadcrumb nhiều cấp, tombstone qua reindex; contract/ADR-026, regression và cutover/replay local |

Storefront search hiện dùng [một card/Product và cheapest eligible SKU](./product-card-search.md), total_products và PIT cursor theo Search ADR-020.

Cải thiện sau review có backend: [storefront-ux-fix-2026-10-17.md](./storefront-ux-fix-2026-10-17.md) — menu mobile, CTA cố định, nội dung voucher theo rule và catalog minh họa; giữ nguyên bộ lọc.

Tiếp theo: [catalog-filter-fix-2026-10-17.md](./catalog-filter-fix-2026-10-17.md) — thu gọn bộ lọc, policy Search theo danh mục, chuyển thông số SKU và loại QA khỏi discovery.
