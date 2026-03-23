# Promotion Service

## Tổng Quan

Java Spring Boot microservice quản lý toàn bộ hệ thống Khuyến mãi (Promotions) cho E-Commerce Platform, bao gồm:
- **Cart Vouchers** (Mã giảm giá giỏ hàng): PLATFORM_WIDE, SELLER_LIMITED, LIMITED_FLASH
- **Markdown Rules** (Giảm giá gạch chéo): giảm giá tự động trên danh sách sản phẩm
- **Flash Sale SPOP Claiming**: thuật toán O(1) giật mã Flash Sale bằng Redis SPOP
- **Budget Reservation Saga**: TRY/CONFIRM/CANCEL cho Checkout integration
- **Transactional Outbox Pattern**: đảm bảo Exactly-once Kafka publishing

## Kiến Trúc

### Clean/Hexagonal Architecture (4 layers)

```
domain/          → Entities, Enums, Rule Engine, Repository Interfaces
application/     → Services, Port Interfaces, DTOs, Exceptions
infrastructure/  → Redis, Kafka, Configs, Workers, Cache
presentation/    → REST Controllers, Request/Response DTOs, Exception Handler
```

### Tech Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.4.3, Java 21 |
| Build     | Gradle |
| Database  | PostgreSQL + JSONB (Flyway migrations) |
| L1 Cache  | Caffeine (refreshAfterWrite, anti-Thundering Herd) |
| L2 Cache  | Redis Cluster (Lettuce client) |
| Messaging | Apache Kafka (Spring Kafka) |
| ORM       | Spring Data JPA + Hibernate + Hypersistence Utils |
| Scheduling| @EnableScheduling (Workers & CronJobs) |

### Database Schema (9 tables)

1. `campaigns` — Chiến dịch (nhóm promotions)
2. `promotions` — Mã giảm giá giỏ hàng (có JSONB `conditions_rule` cho Rule Engine AST)
3. `promotion_budgets` — Ngân sách (tách riêng bảng tránh Row Lock contention)
4. `coupon_claims` — Flash Sale SPOP claiming pool
5. `user_coupon_wallets` — Ví voucher user (UNIQUE user_id + promotion_id)
6. `coupon_usages` — Sổ cái phân tán (RESERVED → REDEEMED → CANCELLED/REFUNDED)
7. `promotion_event_idempotency` — Chống lặp Kafka event
8. `promotion_outbox` — Transactional Outbox cho Kafka publishing
9. `markdown_rules` — Luật giảm giá gạch chéo (polymorphic ref_id)

### Redis Lua Scripts (5 scripts)

| Script | File | Mô tả |
|--------|------|--------|
| Script 1 | `spop_claiming.lua` | Flash Sale O(1) SPOP Claiming |
| Script 2 | `budget_reservation.lua` | TRY — Giữ chỗ ngân sách |
| Script 3 | `commit_redeem.lua` | CONFIRM — Xác nhận mua thành công |
| Script 4 | `rollback_cancel.lua` | CANCEL — Nhả ngân sách khi hủy đơn |
| Script 5 | `refund_budget.lua` | REFUND — Hoàn ngân sách đổi trả hàng |

### Rule Engine

Custom JSON AST Evaluator sử dụng Composite + Visitor patterns:
- `RuleNode` (interface) → `LogicalNode` (AND/OR) + `ConditionNode` (9 operators)
- Jackson `@JsonTypeInfo` polymorphic deserialization từ JSONB
- `RuleValidator` kiểm tra max depth 10, field whitelist, payload 64KB limit

### Kafka Integration

**Inbound Topics:**
- `t.order.events` → lắng nghe ORDER_SUCCESS, ORDER_CANCELLED, ORDER_REFUNDED

**Outbound Topics (via Outbox Pattern):**
- `t.promotion.markdown.events` → MARKDOWN_RULE_ACTIVATED/DEACTIVATED/UPDATED
- `t.promotion.coupon.events` → COUPON_CLAIMED, COUPON_RESERVED/REDEEMED/CANCELLED/REFUNDED
- `t.promotion.budget.events` → BUDGET_EXHAUSTED

### Background Workers & CronJobs

| Worker | Schedule | Mô tả |
|--------|----------|--------|
| Outbox Relay Worker | 500ms polling | Poll & publish outbox events to Kafka |
| Pending Claims Sweeper | 5 phút | Dọn rác SPOP pending claims |
| Reservation TTL Sweep | 2 phút | Phát hiện & nhả budget bị khóa quá 15 phút |
| Coupon Claims Cleanup | Daily 3:00 AM | Hard delete expired coupon_claims (chunked batch) |
| Idempotency Cleanup | Daily 4:00 AM | Xóa idempotency keys quá 7 ngày |
| Outbox GC | Daily 5:00 AM | Xóa PUBLISHED/DEAD outbox events quá 7 ngày |
| Markdown Expiry Scanner | 10 phút | Quét & deactivate expired markdown rules |

## Cấu Hình

- **Port:** 8085
- **Profile:** `dev` (default), xem `application-dev.yml`
- **PostgreSQL:** `promotion_db`
- **Redis:** localhost:6379
- **Kafka:** localhost:9092

## Tài Liệu Thiết Kế

Tất cả design documents nằm tại `docs/promotion/`:
- [technology_choice.md](../../../docs/promotion/technology_choice.md)
- [promotion_workflow.md](../../../docs/promotion/promotion_workflow.md)
- [promotion_db_design.md](../../../docs/promotion/promotion_db_design.md)
- [promotion_events.md](../../../docs/promotion/promotion_events.md)
- [redis_caching_design.md](../../../docs/promotion/redis_caching_design.md)
- [rule_engine_design.md](../../../docs/promotion/rule_engine_design.md)
- [promotion_outbox_pattern.md](../../../docs/promotion/promotion_outbox_pattern.md)
- [promotion_workers.md](../../../docs/promotion/promotion_workers.md)
- [coupon_claims_deletion_strategy.md](../../../docs/promotion/coupon_claims_deletion_strategy.md)
