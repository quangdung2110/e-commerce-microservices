# User Service - Design Index

Tài liệu tổng hợp cho User Service đã được tách thành các module chi tiết, tương đương cấp độ Product Service.

## 1. Mục tiêu thiết kế

1. Áp dụng contract-first giữa User Service và các service tiêu thụ.
2. Mô tả đầy đủ workflow cho profile, address, KYC, và compliance lifecycle.
3. Chuẩn hóa thiết kế DB, cache, Kafka events, workers, và state machine.

## 2. Danh sách tài liệu chi tiết

1. [Architecture Recommendations](user_architecture_recommendations.md)
2. [Database Schema (Write Store)](user_db_design.md)
3. [Caching Schema (Redis)](user_caching_schema.md)
4. [Events Structure and Kafka Topics](user_events.md)
5. [State Machine and Lifecycle](user_state_machine.md)
6. [Background Workers and Jobs](user_workers.md)
7. [Workflows (API and Data Flow)](user_workflow.md)

## 3. Runtime và hướng triển khai

1. Runtime đề xuất: Go (phù hợp đặc tính read-heavy và mục tiêu latency thấp).
2. Cơ sở dữ liệu: PostgreSQL (write model source of truth).
3. Cache: Redis cache-aside kết hợp soft invalidation.
4. Event bus: Kafka với transactional outbox relay/CDC.

## 4. Phụ thuộc liên service

1. Upstream consume: Auth, Order, Payment events.
2. Downstream produce: Auth, Order, Notification, Review, Seller, Analytics consume `t.user.events`.
3. Compliance flows là bắt buộc cho account deletion và anonymization.

