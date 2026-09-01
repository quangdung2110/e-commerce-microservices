# Contract Changelog

Chỉ ghi thay đổi có thể được provider hoặc consumer quan sát. Refactor nội bộ
không cần xuất hiện ở đây.

## 2026-09-02

- Thêm Product HTTP API v1 ở trạng thái proposed: admin quản lý Product/SKU/media,
  category/attribute và internal mTLS export/snapshot cho Search. Contract không chứa
  stock field và dùng ETag catalog revision cho Product aggregate mutation.

## 2026-08-27

- Thêm Auth Event v1: CloudEvents/JSON Schema cho identity lifecycle và security
  notification request.
- Thêm Auth Internal Notification Delivery API v1: Notification Service resolve
  email và nội dung secret theo `delivery_id` qua mTLS, không qua Kafka.

## 2026-08-25

- Khởi tạo contract foundation độc lập với Go/Java.
- Thêm service và interaction catalog.
- Thêm pilot Cart HTTP, Product gRPC và Cart Kafka event.
- Chuẩn hóa Money, Problem Details, CloudEvents, versioning và validation flow.
