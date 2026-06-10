# Product Service - Lựa Chọn Ngôn Ngữ Lập Trình

## Quyết Định: ✅ Java (Spring Boot)

---

## 1. Tóm Tắt Đặc Tính Service

|| Đặc tính | Mô tả |
||:---|:---|
| **Kiến trúc dữ liệu** | PostgreSQL-First (Write Model / Source of Truth) + Redis Cache |
| **State Machine** | Spring State Machine (FSM) quản lý lifecycle sản phẩm (DRAFT → ACTIVE → HIDDEN). Không có trạng thái PENDING_REVIEW hay BANNED vì kiến trúc áp dụng mô hình Admin-only, không có seller/shop. Admin tạo sản phẩm, có thể ẩn/hiện sản phẩm. Xem [State Machine](product_state_machine.md). |
| **Concurrency - State Transitions** | Pessimistic Locking (FOR NO KEY UPDATE) cho Admin API transitions (SUBMIT/HIDE/SHOW), tuân thủ lock order Product → SKU để chống deadlock. |
| **Concurrency - Inventory Sync** | **Two-Phase Lock-Free Architecture**: Phase 1 lock-free trên Redis (Lua CAS), Phase 2 batch Optimistic Locking trên PostgreSQL. Không dùng Pessimistic Locking cho từng inventory event vì hot SKU contention quá cao. Xem [Inventory Sync Optimization](inventory_sync_optimization.md). |
| **Concurrency - Markdown Worker** | Distributed Lock (Redis SETNX) + lock_timeout/retry policy khi nhiều instance worker cùng xử lý. |
| **Caching** | Redis (Point-Read, Rate Limiting, Markdown Marker) |
| **Outbox Pattern** | Debezium (Change Data Capture) — Real-time event streaming |
| **Cross-Service** | Consumer: Promotion Markdown Events, Inventory Events. Producer: Product Events cho Search/Notification/Promotion (state-change hooks) |
| **Logic nghiệp vụ** | Category validation, SKU management, Markdown price calculation, Auto-hide/reactivate |

---

## 2. Lý Do Chọn Java (Spring Boot)

### 2.1. Spring State Machine — Quản Lý Lifecycle Sản Phẩm
Product Service sử dụng **Finite State Machine (FSM)** đơn giản với 3 trạng thái (DRAFT → ACTIVE → HIDDEN) và Guard Conditions. Áp dụng mô hình Admin-only, không có seller/shop nên không có PENDING_REVIEW hay BANNED:
- **Spring Statemachine** framework cung cấp sẵn FSM declarative configuration, Guard functions, State listeners.
- Config FSM bằng Java code:
  ```java
  transitions.withExternal()
      .source(DRAFT).target(ACTIVE).event(SUBMIT)
      .guard(hasRequiredAttributes());
  ```
- Go không có FSM framework tương đương mature — phải tự implement bằng switch-case hoặc map transitions.


### 2.2. Spring `@Transactional` & Debezium CDC — Outbox Pattern
Luồng Create/Update Product ghi **cùng lúc 3-4 bảng** (products, product_skus, product_outbox_events, product_status_history) trong 1 Transaction:
- `@Transactional` của Spring = 1 annotation, không cần viết `BEGIN/COMMIT/ROLLBACK`.
- Debezium CDC đọc trực tiếp WAL từ database để publish event ra Kafka — realtime, không làm ảnh hưởng hiệu năng database.
- Pessimistic Locking (FOR NO KEY UPDATE / FOR UPDATE) cho Admin transitions đảm bảo atomicity và chống deadlock với Markdown Worker. Optimistic Locking chỉ dùng ở Phase 2 Inventory Sync (batch).

### 2.3. Markdown Price Calculation — Domain Logic Phức Tạp
Worker nhận `MARKDOWN_CREATED` từ Promotion Service phải:
- Query SKU theo `scope_type` (PRODUCT/CATEGORY).
- Batch tính toán `promotional_price`.
- Batch update DB + Insert `product_markdown_applied` + Insert outbox events.
- Logic này phù hợp với Java Service Layer + Repository Pattern hơn là Go imperative style.

---

## 3. Tại Sao Không Chọn Golang (gRPC)

|| Hạn chế | Ảnh hưởng |
||:---|:---|
| **Không có Spring State Machine** | Phải tự implement FSM — error-prone, khó maintain khi thêm states/transitions mới |
| **Thiếu Caffeine Cache** | Category validation hiện dùng trực tiếp PostgreSQL, không cần Caffeine. |
| **Transaction boilerplate** | Outbox Pattern cần ghi 3-4 bảng atomically — Go phải manual transaction management |

---

## 4. Tech Stack Chi Tiết

|| Component | Công nghệ |
||:---|:---|
| **Framework** | Spring Boot 3.x + gRPC + Spring State Machine |
| **Cache L2** | Redis (via Lettuce, RedisJSON) — Point-Read, Rate Limiting |
| **Database** | PostgreSQL + Spring Data JPA / Hibernate |
| **Kafka** | Spring Kafka (`@KafkaListener` for Promotion/Inventory events) |
| **Outbox** | Debezium CDC (Change Data Capture) |
| **Build** | Maven + JDK 21 |
