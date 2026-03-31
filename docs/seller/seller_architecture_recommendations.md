# Seller Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Design Index](seller_design.md)

---

Tài liệu này tổng hợp các khuyến nghị kiến trúc cho Seller Service trong bối cảnh marketplace quy mô lớn.

## 1. Boundary và Ownership

1. Seller Service sở hữu seller lifecycle: onboarding, verification, profile, config, commission, payout.
2. User Service sở hữu account profile/KYC cấp user; Seller chỉ consume kết quả cần thiết.
3. Product và Search consume seller profile/status để quyết định visibility/discoverability.
4. Payment Service sở hữu payment execution; Seller Service sở hữu payout business state.

## 2. Eventing và Ordering

1. Topic produce chính: `t.seller.events`, partition key là `seller_id`.
2. Event envelope theo CloudEvents, bắt buộc có `correlation_id`.
3. Ordering guards theo event type:
   - `profile_version` cho profile events.
   - `status_version` cho status events.
   - `performance_version` cho performance events.
4. Consumer bắt buộc idempotent theo `event_id`.
5. Event schema additive-only; breaking change phải version hoá.

## 3. Consistency và Concurrency

1. `sellers.version` là aggregate root version.
2. Lock ordering transaction: `sellers` -> child tables (`seller_profiles`, `seller_configs`, `seller_performance_metrics`).
3. Commission/payout updates phải ghi transactionally vào ledger + outbox.
4. Worker cache-selfheal không được ghi ngược vào business DB.
5. Batch jobs phải chunk theo seller/time-window để giảm lock contention.

## 4. Caching Strategy

1. L1 Caffeine + L2 Redis cho profile/status/commission rules.
2. Soft invalidation (`SET` overwrite) là mặc định.
3. Dynamic TTL cho payout summary theo chu kỳ.
4. Stampede protection với singleflight + distributed lock.
5. Batch cache refresh bằng pipeline.

## 5. Contract Governance

1. Contract-first cho gRPC point-read APIs (`GetSellerProfile`, `GetCommissionRate`).
2. Product/Order/Search/Payment là downstream critical, bắt buộc contract tests liên service.
3. Event payload tối thiểu hóa PII, chỉ giữ field nghiệp vụ cần thiết.
4. Có migration strategy rõ cho commission-rule schema changes.

## 6. Security và Compliance

1. Mã hoá at-rest cho tax_id, bank metadata, legal docs.
2. Audit logs bắt buộc cho verification reviews, status changes, payout approvals.
3. Separation of duties cho approve/reject verification và payout release.
4. Bắt buộc verify webhook signature với payment callbacks.
5. Áp dụng least-privilege cho admin/seller/internal service accounts.

## 7. Operability

1. Theo dõi metrics: commission lag, payout lag/failure ratio, cache hit ratio, outbox lag, consumer lag, DLQ.
2. Có runbook cho payout retry, ledger reconciliation, cache rebuild.
3. Canary rollout theo seller tiers/cohorts.
