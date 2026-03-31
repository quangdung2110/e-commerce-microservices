# Seller Service - Workflows (API and Data Flow)

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Architecture Recommendations](seller_architecture_recommendations.md)

---

Tài liệu này mô tả trình tự nghiệp vụ chi tiết của Seller Service, bao gồm cả luồng đồng bộ sang cache và Kafka.

## 1. Gợi ý base path

`/api/v1/sellers/...`

## 2. Workflow quan trọng: Onboarding (`POST /api/v1/sellers/onboarding`)

### Các bước xử lý

1. Validate payload + idempotency key.
2. Gọi User Service để kiểm tra điều kiện khởi tạo (KYC/account status).
3. Mở transaction:
   - insert `sellers`.
   - insert `seller_profiles` mặc định.
   - insert `seller_configs` mặc định.
   - insert outbox `SELLER_ONBOARDED`.
4. Commit.
5. Ghi cache `seller:profile:{seller_id}` và `seller:status:{seller_id}`.
6. Trả `201 Created`.

### Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as Seller Service
    participant U as User Service
    participant D as PostgreSQL
    participant R as Redis
    participant K as Kafka

    C->>S: POST /api/v1/sellers/onboarding
    S->>S: Validate + idempotency check
    S->>U: gRPC CheckUserKycStatus(user_id)
    U-->>S: KYC status
    S->>D: BEGIN
    S->>D: INSERT sellers
    S->>D: INSERT seller_profiles
    S->>D: INSERT seller_configs
    S->>D: INSERT seller_outbox_events(SELLER_ONBOARDED)
    S->>D: COMMIT
    S->>R: SET seller:profile:{seller_id}
    S->>R: SET seller:status:{seller_id}
    S-->>C: 201 Created
    D-->>K: Publish SELLER_ONBOARDED
```

## 3. Workflow quan trọng: Submit verification (`POST /api/v1/sellers/{id}/verification/submit`)

### Các bước xử lý

1. Validate hồ sơ và metadata.
2. Upload hồ sơ lên object storage.
3. Mở transaction:
   - insert `seller_verifications` status `PENDING`.
   - update `sellers.status = PENDING_VERIFICATION`, `version = version + 1`.
   - insert `seller_verification_history`.
   - insert outbox `SELLER_VERIFICATION_STARTED`.
4. Commit.
5. Overwrite cache status.
6. Trả `202 Accepted`.

### Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as Seller Service
    participant O as Object Storage
    participant D as PostgreSQL
    participant R as Redis
    participant K as Kafka

    C->>S: POST /sellers/{id}/verification/submit
    S->>S: Validate documents
    S->>O: Upload legal docs
    O-->>S: document_urls
    S->>D: BEGIN
    S->>D: INSERT seller_verifications(status=PENDING)
    S->>D: UPDATE sellers SET status=PENDING_VERIFICATION, version=version+1
    S->>D: INSERT seller_verification_history
    S->>D: INSERT seller_outbox_events(SELLER_VERIFICATION_STARTED)
    S->>D: COMMIT
    S->>R: SET seller:status:{seller_id}
    S-->>C: 202 Accepted
    D-->>K: Publish SELLER_VERIFICATION_STARTED
```

## 4. Workflow quan trọng: Review verification (`PATCH /api/v1/sellers/{id}/verification/review`)

### Các bước xử lý

1. Kiểm tra quyền reviewer.
2. Lock `sellers` và `seller_verifications` rows theo thứ tự chuẩn.
3. Cập nhật kết quả review.
4. Áp dụng state transition theo FSM.
5. Ghi history và outbox `SELLER_VERIFICATION_COMPLETED`.
6. Commit, refresh cache, trả `200`.

### Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant A as Admin
    participant S as Seller Service
    participant D as PostgreSQL
    participant R as Redis
    participant K as Kafka

    A->>S: PATCH /verification/review
    S->>S: AuthZ + validate command
    S->>D: BEGIN
    S->>D: SELECT sellers FOR UPDATE
    S->>D: SELECT seller_verifications FOR UPDATE
    S->>D: UPDATE seller_verifications
    S->>D: UPDATE sellers(status/version)
    S->>D: INSERT seller_verification_history
    S->>D: INSERT seller_outbox_events(SELLER_VERIFICATION_COMPLETED)
    S->>D: COMMIT
    S->>R: Overwrite seller status/profile caches
    S-->>A: 200 OK
    D-->>K: Publish SELLER_VERIFICATION_COMPLETED
```

## 5. Workflow quan trọng: Change seller status (`PATCH /api/v1/sellers/{id}/status`)

### Các bước xử lý

1. Validate command theo state machine.
2. Update trạng thái bằng optimistic lock (`version`).
3. Insert `seller_verification_history` (hoặc status history thống nhất).
4. Insert outbox `SELLER_STATUS_CHANGED`.
5. Commit.
6. Soft refresh cache status/profile.
7. Downstream xử lý visibility qua Kafka.

## 6. Workflow quan trọng: Commission calculation (async)

**Trigger:** `ORDER_PAID` từ `t.order.events`.

### Các bước xử lý

1. Worker nhận event, dedup theo `event_id`.
2. Resolve commission rule theo seller/category.
3. Transaction:
   - insert `seller_commission_ledger`.
   - update payout accrual summary.
   - insert outbox `SELLER_COMMISSION_CALCULATED`.
4. Commit.
5. Update cache payout summary theo batch pipeline.

### Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka(t.order.events)
    participant W as Commission Worker
    participant D as PostgreSQL
    participant R as Redis
    participant KO as Kafka(t.seller.events)

    K-->>W: ORDER_PAID
    W->>W: Dedup event_id + resolve commission rule
    W->>D: BEGIN
    W->>D: INSERT seller_commission_ledger
    W->>D: UPDATE payout accrual
    W->>D: INSERT seller_outbox_events(SELLER_COMMISSION_CALCULATED)
    W->>D: COMMIT
    W->>R: Pipeline update seller:payout_summary:* 
    D-->>KO: Publish SELLER_COMMISSION_CALCULATED
```

## 7. Workflow quan trọng: Payout settlement (async)

### Các bước xử lý

1. Scheduler chạy theo kỳ quyết toán.
2. Tạo payout records `PENDING` cho từng seller.
3. Trigger payment transfer (bounded concurrency).
4. Nhận callback success/fail từ Payment Service.
5. Cập nhật `seller_payouts.status`, ghi outbox payout event.
6. Commit và refresh payout caches.

### Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant CR as Scheduler
    participant W as Payout Worker
    participant D as PostgreSQL
    participant P as Payment Service
    participant K as Kafka(t.payment.events)
    participant KO as Kafka(t.seller.events)

    CR->>W: Trigger payout cycle
    W->>D: Query accruals by period
    W->>D: INSERT seller_payouts(status=PENDING)
    W->>P: Initiate payout batch
    P-->>W: Accepted
    K-->>W: PAYOUT_SUCCESS/PAYOUT_FAILED callback
    W->>D: UPDATE seller_payouts status
    W->>D: INSERT seller_outbox_events(SELLER_PAYOUT_COMPLETED or FAILED)
    W->>D: COMMIT
    D-->>KO: Publish payout event
```

## 8. Workflow quan trọng: Performance scoring (async)

1. Worker tổng hợp tín hiệu từ order/review/inventory.
2. Tính score theo trọng số chuẩn hoá.
3. Update `seller_performance_metrics` + tăng `performance_version`.
4. Insert outbox `SELLER_PERFORMANCE_SCORE_UPDATED`.

## 9. Internal gRPC workflows

1. `GetSellerProfile`: L1 -> L2 -> DB fallback.
2. `ValidateSellerStatus`: ưu tiên consistency, stale guard theo `status_version`.
3. `GetCommissionRate`: đọc commission rule cache, miss thì DB.
4. `GetPrimaryWarehouse`: lookup warehouse cache/DB.

## 10. Error handling và resilience rules

1. Optimistic lock conflict trả `409`, caller có retry policy.
2. Redis down: fallback DB có rate limit/circuit breaker.
3. Callback payout duplicate: idempotent theo `payout_id` + `provider_reference`.
4. Consumer lỗi tạm thời: retry; lỗi payload/schema: DLQ.

## 11. REST contract schema mẫu

### 11.1 Onboarding - Request

```json
{
    "user_id": "u-10001",
    "company_name": "ABC Trading Co.",
    "tax_id": "0312345678",
    "store_name": "ABC Official Store"
}
```

### 11.2 Onboarding - Response

```json
{
    "seller_id": "s-9001",
    "user_id": "u-10001",
    "status": "PENDING_ONBOARDING",
    "verification_status": "PENDING",
    "state_version": 1,
    "created_at": "2026-03-31T10:00:00Z"
}
```

### 11.3 Review verification - Request

```json
{
    "decision": "APPROVE",
    "reason": "Hồ sơ hợp lệ",
    "expected_version": 2
}
```

## 12. gRPC contract schema mẫu

```proto
syntax = "proto3";

service SellerService {
    rpc GetSellerProfile(GetSellerProfileRequest) returns (GetSellerProfileResponse);
    rpc ValidateSellerStatus(ValidateSellerStatusRequest) returns (ValidateSellerStatusResponse);
    rpc GetCommissionRate(GetCommissionRateRequest) returns (GetCommissionRateResponse);
    rpc GetPrimaryWarehouse(GetPrimaryWarehouseRequest) returns (GetPrimaryWarehouseResponse);
}

message GetSellerProfileRequest {
    string seller_id = 1;
}

message GetSellerProfileResponse {
    string seller_id = 1;
    string store_name = 2;
    string status = 3;
    int64 profile_version = 4;
    string updated_at = 5;
}

message ValidateSellerStatusRequest {
    string seller_id = 1;
}

message ValidateSellerStatusResponse {
    string seller_id = 1;
    string status = 2;
    int64 status_version = 3;
    bool can_sell = 4;
}

message GetCommissionRateRequest {
    string seller_id = 1;
    string category_id = 2;
}

message GetCommissionRateResponse {
    string seller_id = 1;
    string category_id = 2;
    double commission_rate = 3;
    string policy_version = 4;
}

message GetPrimaryWarehouseRequest {
    string seller_id = 1;
}

message GetPrimaryWarehouseResponse {
    string seller_id = 1;
    string warehouse_id = 2;
    string warehouse_name = 3;
    string warehouse_status = 4;
}
```
