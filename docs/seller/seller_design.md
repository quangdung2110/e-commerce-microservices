# Seller Service - Design Index

Tài liệu tổng hợp cho Seller Service đã được tách thành các module chi tiết, với cấp độ mô tả tương đương Product Service.

## 1. Mục tiêu thiết kế

1. Thiết kế contract-first cho seller lifecycle và các luồng tài chính.
2. Mô tả đầy đủ workflow onboarding, verification, status, commission, và payout.
3. Chuẩn hóa thiết kế DB, cache L1/L2, Kafka events, workers, và state machine.

## 2. Danh sách tài liệu chi tiết

1. [Architecture Recommendations](seller_architecture_recommendations.md)
2. [Database Schema (Write Store)](seller_db_design.md)
3. [Caching Schema (Redis + L1)](seller_caching_schema.md)
4. [Events Structure and Kafka Topics](seller_events.md)
5. [State Machine and Lifecycle](seller_state_machine.md)
6. [Background Workers and Jobs](seller_workers.md)
7. [Workflows (API and Data Flow)](seller_workflow.md)

## 3. Runtime và hướng triển khai

1. Runtime đề xuất: Java/Spring Boot.
2. Cơ sở dữ liệu: PostgreSQL với ledger và payout tables.
3. Cache: Caffeine L1 kết hợp Redis L2.
4. Event bus: Kafka với transactional outbox relay/CDC.

## 4. Phụ thuộc liên service

1. Upstream consume: Order, Review, Payment, Inventory events.
2. Downstream produce: Product, Search, Order, Payment, Analytics consume `t.seller.events`.
3. Luồng visibility listing phụ thuộc chặt chẽ vào contract `SELLER_STATUS_CHANGED`.

