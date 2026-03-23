# Product Service - Lựa Chọn Ngôn Ngữ Lập Trình

## Quyết Định: ✅ Java (Spring Boot)

---

## 1. Tóm Tắt Đặc Tính Service

| Đặc tính | Mô tả |
|:---|:---|
| **Kiến trúc dữ liệu** | PostgreSQL-First (Write Model / Source of Truth) + Redis Validation Cache |
| **State Machine** | Spring State Machine (FSM) quản lý lifecycle sản phẩm (DRAFT → ACTIVE → BANNED) |
| **Concurrency** | Optimistic Locking (`version`) cho State Transitions, Pessimistic Locking (`FOR NO KEY UPDATE`) cho Inventory Sync, Cache Stampede Protection (ReentrantLock) |
| **Caching** | 2-Tier: Caffeine L1 (Category Tree) + Redis L2 (Point-Read, Idempotency) |
| **Outbox Pattern** | Debezium (Change Data Capture) — Real-time event streaming |
| **Cross-Service** | Consumer: Promotion Markdown Events, Inventory Events. Producer: Product Events cho Search/Cart |
| **Logic nghiệp vụ** | Category validation, SKU management, Markdown price calculation, Auto-hide/reactivate |

---

## 2. Lý Do Chọn Java (Spring Boot)

### 2.1. Spring State Machine — Quản Lý Lifecycle Sản Phẩm
Product Service sử dụng **Finite State Machine (FSM)** phức tạp với nhiều transitions (DRAFT → PENDING_REVIEW → ACTIVE → HIDDEN → BANNED) và Guard Conditions:
- **Spring Statemachine** framework cung cấp sẵn FSM declarative configuration, Guard functions, State listeners.
- Config FSM bằng Java code:
  ```java
  transitions.withExternal()
      .source(DRAFT).target(PENDING_REVIEW).event(SUBMIT_FOR_REVIEW)
      .guard(hasRequiredAttributes());
  ```
- Go không có FSM framework tương đương mature — phải tự implement bằng switch-case hoặc map transitions.

### 2.2. Caffeine L1 + Redis L2 — Dual-Layer Validation
Tương tự Promotion Service, Product Service sử dụng **Caffeine Local Cache** cho Category Tree validation:
- Category Tree được cache vào RAM JVM — truy vấn `O(1)` khi validate attribute requirements mỗi khi Seller tạo/sửa sản phẩm.
- `expireAfterWrite` (Mặc định 1 phút) của Caffeine đóng vai trò Safety Fallback phòng khi việc xoá cache mượt mà thông qua Redis Pub/Sub bị nghẽn mạng (Miss event).

### 2.3. Spring `@Transactional` & Debezium CDC — Outbox Pattern
Luồng Create/Update Product ghi **cùng lúc 3-4 bảng** (products, product_skus, product_outbox_events, product_status_history) trong 1 Transaction:
- `@Transactional` của Spring = 1 annotation, không cần viết `BEGIN/COMMIT/ROLLBACK`.
- Worker Debezium CDC đọc trực tiếp WAL từ database để publish event ra Kafka — realtime, không làm ảnh hưởng hiệu năng database như DB Polling.
- Optimistic Locking = `@Version` annotation trên entity — JPA tự handle `version = version + 1` check.

### 2.4. Markdown Price Calculation — Domain Logic Phức Tạp
Worker nhận `MARKDOWN_CREATED` từ Promotion Service phải:
- Query SKU theo `scope_type` (PRODUCT/CATEGORY).
- Batch tính toán `promotional_price`.
- Batch update DB + Insert `product_markdown_applied` + Insert outbox events.
- Logic này phù hợp với Java Service Layer + Repository Pattern hơn là Go imperative style.

---

## 3. Tại Sao Không Chọn Golang (gRPC)

| Hạn chế | Ảnh hưởng |
|:---|:---|
| **Không có Spring State Machine** | Phải tự implement FSM — error-prone, khó maintain khi thêm states/transitions mới |
| **Thiếu Caffeine Cache** | Go thiếu L1 Object Cache mature — Category Tree validation sẽ phải gọi Redis mỗi lần |
| **Transaction boilerplate** | Outbox Pattern cần ghi 3-4 bảng atomically — Go phải manual transaction management |
| **Thiếu `@Version` (ORM)** | Optimistic Locking phải tự kiểm tra `affected_rows = 0` thay vì JPA auto-handle |

---

## 4. Tech Stack Chi Tiết

| Component | Công nghệ |
|:---|:---|
| **Framework** | Spring Boot 3.x + gRPC + Spring State Machine |
| **Cache L1** | Caffeine (Category tree, attributes) |
| **Cache L2** | Redis (via Lettuce) + Snappy (Compression) — Point-Read, Idempotency, Rate Limiting |
| **Database** | PostgreSQL + Spring Data JPA / Hibernate |
| **Kafka** | Spring Kafka (`@KafkaListener` for Promotion/Inventory events) |
| **Outbox** | Debezium CDC (Change Data Capture) |
| **Build** | Maven + JDK 21 |
