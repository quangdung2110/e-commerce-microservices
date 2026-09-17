---
doc_id: checkout-adr-001
service: checkout
status: accepted
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/architecture.md
  - docs/checkout/README.md
---

# ADR-001 — Checkout là BFF/Aggregator stateless, tách khỏi Order Service

## Trạng thái

`Accepted` theo thiết kế cũ. Chưa có implementation evidence.

## Bối cảnh

Khách hàng thao tác liên tục trên màn hình Checkout: đổi địa chỉ, thử 5–7 mã giảm
giá, so sánh số tiền phải trả. Nếu những tương tác “nháp” này gõ trực tiếp vào
Order Service, PostgreSQL của Order bị vắt kiệt IOPS dẫn đến timeout khi khách
khác đang thực sự tạo đơn, đặc biệt trong Flash Sale. Mobile App nếu không có BFF
phải gọi nhiều API riêng lẻ (Cart, Promotion, Inventory, User) và tự xử lý
warehouse selection theo từng item, gây lỗi bảo mật (client đổi giá), logic App
nặng, tốn pin và băng thông.

## Quyết định

- Tách tính năng “Thanh toán/Chốt đơn” thành **Checkout Service** đóng vai trò
  Aggregator và Orchestrator cấp cao cho Frontend; Order Service chỉ nhận lệnh
  tạo đơn khi user thực sự xác nhận.
- Checkout **không sở hữu database bền vững**; chỉ dùng Redis lưu session/cache
  có TTL (không PostgreSQL, không Kafka, không Temporal).
- Preview gọi song song downstream (`Goroutines` hoặc `CompletableFuture`) theo
  dependency graph Wave 1/2/3 và tính giá tập trung server-side.
- Ranh giới với Cart: tăng/giảm số lượng, thêm/xóa sản phẩm thuộc Cart Service;
  Checkout chỉ nhận snapshot SKU + quantity đã chuẩn hóa; payload cố mutate
  quantity → `400 INVALID_CHECKOUT_MUTATION_SCOPE`.
- Ranh giới với Order: Order quản lý vòng đời đơn, lưu DB, chạy Temporal Saga;
  Checkout hiển thị bảng tính tiền, bắt lỗi logic trước khi chốt, tạo token bảo
  mật.
- Checkout không duy trì cache địa chỉ riêng; User Service sở hữu cache
  `user:caches:addresses:{user_id}` (double caching là dư thừa và dễ loạn trạng
  thái).

## Phương án đã cân nhắc

- **Frontend gọi trực tiếp Order Service cho preview:** đơn giản nhưng Order DB
  chịu toàn bộ traffic nháp; không chọn.
- **Frontend tự aggregate nhiều API:** không cần service mới nhưng client bị hack
  đổi giá, logic app nặng; không chọn.
- **BFF stateless + Redis session (chọn):** chặn 99% traffic xem thử trước khi
  chạm Order DB; tính toán tập trung server-side; scale ngang dễ.

## Hệ quả

- Tích cực: Order Service chuyên tâm tác vụ ghi/đọc lõi; một request duy nhất từ
  Frontend; giá tính server-side.
- Tiêu cực: thêm một service và một keyspace Redis; mọi state là tạm thời (mất
  Redis = user Preview lại); Checkout phụ thuộc đồng thời nhiều downstream nên cần
  timeout/fallback theo từng scope.
- Constraint: không query DB service khác; không khóa kho/budget ở Preview; không
  mutate quantity.

## Ảnh hưởng migration

Chưa có code. Frontend phải đi qua Preview/Submit thay vì gọi Order; Cart API phải
được gọi trước khi Preview khi đổi số lượng.

## Xác minh

Khi có code: không có kết nối DB trong Checkout; test `INVALID_CHECKOUT_MUTATION_SCOPE`;
Preview không tạo bản ghi ở Order; Wave 1 gọi song song (trace).
