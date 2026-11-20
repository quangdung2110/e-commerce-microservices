---
doc_id: payment-architecture
service: payment
scope: target
status: implemented
owners:
  - Payment Service team
evidence:
  - contracts/http/payment/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/catalog/interactions.yaml
  - java/services/payment-service/src/main/java/com/platform/paymentservice
  - java/services/payment-service/src/test/java/com/platform/paymentservice
verified_against_commit: null
---

# Payment Service — Kiến trúc

> **Đối chiếu runtime 2026-10-11:** xem [phạm vi hiện tại và năng lực chưa triển khai](./runtime-status.md)
> và [ADR-023](./decisions/ADR-023-reconciliation-ownership-and-audit-corrections.md). Các mục tiêu KMS, metric nghiệp vụ,
> heartbeat, health/DR/export và RBAC bên ngoài không phải bằng chứng đã được runtime hiện thực.

> Kiến trúc mục tiêu của Payment Service: PostgreSQL-first, Stripe SDK direct,
> webhook async qua Kafka, outbox CDC, side effect provider durable. File này trả
> lời "thành phần nào, ranh giới nào, pattern nào, vì sao"; hành vi chi tiết ở
> `domain.md`/`workflows.md`, schema ở `data.md`, vận hành ở `operations.md`. Runtime,
> migration, Dockerfile và integration tests hiện nằm dưới
> `java/services/payment-service`.

## 1. Bối cảnh kiến trúc

### 1.1. Vị trí trong hệ thống

Payment là **authority duy nhất về trạng thái tiền** của một order. Nó nằm giữa
Order Service (chủ saga) và Stripe (provider), không có bề mặt public cho buyer:

- **Order → Payment (HTTP, sync):** initiate / cancel / refund / query / client-data
  qua Temporal Activity; interaction `order-initiates-payment` trong
  `contracts/catalog/interactions.yaml`.
- **Frontend → Stripe (trực tiếp):** Stripe Elements dùng `client_secret` do Payment
  cấp (Order relay). Payment không nhận request từ buyer.
- **Stripe → Payment (HTTPS webhook):** route public duy nhất của Payment, đi qua
  Gateway với middleware `stripe-webhook` (bypass JWT, giữ raw body, không retry).
- **Payment → Kafka → Order/Analytics:** financial fact v2 qua outbox CDC;
  interaction `payment-publishes-payment-events`.
- **Payment → Stripe (HTTPS egress):** qua mesh `ServiceEntry`, `REGISTRY_ONLY`.

```mermaid
flowchart LR
  subgraph edge[Edge]
    GW[API Gateway<br/>stripe-webhook middleware]
  end
  subgraph mesh[Service mesh - namespace ecommerce]
    O[Order Service<br/>Temporal Activities]
    subgraph pay[Payment Service]
      API[HTTP v1 adapter<br/>command + query]
      WH[Webhook endpoint]
      WP[Webhook Processor]
      SCH[Schedulers]
      APP[Application layer<br/>use case + FSM]
      DOM[Domain<br/>PaymentTransaction · RefundTransaction<br/>ProviderOperation · ReviewCase]
      SA[com.platform.paymentservice.stripe<br/>StripeAdapter]
      OUT[Persistence adapter]
    end
    PG[(PostgreSQL)]
    KR[(Kafka<br/>t.payment.webhooks.raw)]
    KP[(Kafka<br/>t.payment.events.v2)]
    DBZ[Debezium / Kafka Connect]
    AN[Analytics]
  end
  ST[Stripe]
  FE[Frontend<br/>Stripe Elements]

  O -->|mTLS, POST/GET /v1/payments/**| API
  ST -->|POST /api/payments/webhook/stripe| GW --> WH
  WH -->|verified delivery| KR --> WP
  API --> APP
  WP --> APP
  SCH --> APP
  APP --> DOM
  APP --> OUT --> PG
  APP -->|PaymentProviderPort| SA -->|ServiceEntry egress| ST
  PG -->|WAL| DBZ --> KP
  KP --> O
  KP --> AN
  FE -->|client_secret| ST
```

### 1.2. Ranh giới tin cậy

| Ranh giới | Cơ chế | Điều Payment tin / không tin |
|---|---|---|
| Stripe → webhook endpoint | Gateway giữ raw body; Payment ingress giới hạn 256 KiB, verify HMAC + cửa sổ 300s trước inbox/Kafka | Chỉ verified `stripe_event_id` được persist/publish; dependency fail trả `503` để Stripe gửi delivery/signature mới |
| Order → HTTP v1 | Mesh mTLS + `AuthorizationPolicy` (principal `ecommerce/order-service`); header `X-Service-Identity` chỉ để attribution | Tin identity mesh; **không** tin nội dung: fingerprint idempotency, `order_id` khớp transaction, amount ≤ paid |
| Payment → Stripe | Secret key từ Vault; egress chỉ qua `ServiceEntry` | Tin response Stripe là fact provider, nhưng chỉ sau khi bind vào `provider_operations` |
| Kafka out | Chỉ Debezium ghi topic; consumer tự dedup theo CloudEvents `id` | Payment không tin consumer đã nhận; at-least-once |
| Vận hành | Endpoint admin/webhook-log replay/correction yêu cầu quyền quản trị, `actor` + `cause_ref` | Mọi thay đổi trạng thái thủ công đều là transition có audit |

Gateway matrix chỉ có route webhook cho Payment (`docs/gateway/runtime-contract.md`);
không có route `/api/payments/**` nào khác vì buyer đọc payment qua Order.
Gateway rewrite chính xác public `/api/payments/webhook/stripe` sang upstream
`/v1/payments/webhook/stripe`; rewrite chỉ đổi path, raw body và `Stripe-Signature`
phải được forward nguyên vẹn.

### 1.3. Quyết định định hình

Từ Phase 18 chỉ dùng Stripe: bỏ VNPay/MoMo/ZaloPay, COD và abstraction/factory
đa provider; narrow outbound port vẫn cô lập SDK Stripe
([ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)). Từ 2026-09:
side effect provider durable và cancel là fact
([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)); một OLTP
schema không partition ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md));
decline phi terminal, terminal non-capture chỉ sau khi intent đã hủy
([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).
ADR-016…020 lần lượt khóa verified inbox, provider safety horizon + lease/fencing,
provider event checkpoint, Payment-owned subledger/dispute và retention/privacy/DR.

## 2. Thành phần và trách nhiệm

### 2.1. Cấu trúc code (hexagonal)

```
com.platform.paymentservice
├── domain                 # 3 FSM, state/trigger/transition, Money, decline/guard policies
├── application
│   ├── service            # command/query, webhook processor/handler, recovery, reconciliation/review
│   └── port.out           # persistence/provider/clock/cipher/financial commit ports
├── adapter
│   ├── in.http            # controllers, strict JSON, Problem mapping, Ops header validation
│   ├── in.webhook         # exact bytes → verified durable inbox → Kafka
│   ├── in.kafka           # verified webhook listener
│   ├── in.scheduler       # @Scheduled workers
│   ├── out.persistence    # JdbcClient/TransactionTemplate adapters
│   ├── out.kafka          # verified delivery publisher
│   └── out.crypto         # LocalAesGcmSecretCipher; KMS chưa có adapter
└── stripe                 # narrow StripeGateway, provider/retrieve/balance/verifier adapters
```

Package gốc theo quy ước repo (`com.platform.<service>`, như `promotion-service`); ADR-003
gọi package Stripe là `com.payment.stripe` — cùng một thứ, tên hiện hành là
`com.platform.paymentservice.stripe`.

Quy tắc phụ thuộc: `domain` không import gì ngoài JDK; `application` phụ thuộc `domain` và các port, có Jackson để serialize durable JSON; `adapter.*` và `stripe` import `application`/`domain`, không import nhau.
Kiểm tra bằng ArchUnit trong CI (§7).

### 2.2. Thành phần runtime

| Thành phần | Trách nhiệm | Không được làm |
|---|---|---|
| HTTP v1 adapter | Xác thực workload, validate schema, claim DB execution lease/fencing theo fingerprint, gọi use case, map lỗi → problem+json | Chứa FSM; gọi Stripe; tạo thêm correctness authority ngoài PostgreSQL |
| Webhook endpoint | Đọc exact bytes ≤256 KiB, verify/parse tối thiểu trong 300s, encrypt + upsert inbox, produce verified internal CloudEvent; `200` sau durability/Kafka ack | Chạy FSM/gọi Stripe; publish payload chưa xác thực |
| Application/use case | Mở/đóng transaction (TX1/TX2, §3.4), gọi domain, ghi outbox + history trong cùng TX, gọi port provider **ngoài** TX | Publish Kafka trực tiếp; bỏ qua guard; suy luận trạng thái provider |
| Domain | Ba FSM Payment/Refund/Dispute, guard, policy decline, invariant; thuần, test không cần hạ tầng | Biết Stripe type, JPA, Spring |
| `com.platform.paymentservice.stripe` | Narrow single-provider port cho PaymentIntent/Refund/Dispute/BalanceTransaction và ingress verifier; API version pin | Multi-provider factory; lộ `com.stripe.*`; mint provider key khi retry |
| Webhook Processor | Consume verified message, check hash, claim inbox bằng lease/fencing, apply checkpoint/FSM/journal/outbox | Re-verify timestamp; stale worker commit |
| Expiration Scanner | `PENDING/PROCESSING` quá hạn → durable `CANCEL`; quyết định theo actual provider result | Expire/cancel bằng suy luận local |
| Provider-operation Recovery | Replay `REQUESTED`/`UNKNOWN` cùng key; bind hoặc review case | Sinh key mới; suy luận non-capture |
| Refund Status Poller | `Refund.retrieve` cho `PROCESSING` | Gọi khi không có lock; xử lý reversal (thuộc reconciliation) |
| Reconciliation | So DB ↔ Stripe theo cửa sổ (refund `SUCCESS` tới 35 ngày, `RULE-PAY-024`); `CORR` khi không freeze; `reconciliation_runs` | Sửa ngoài transition hợp lệ; chạy nhiều instance |
| Webhook Retry / Manual Replay | Claim trực tiếp verified inbox DB, lease takeover, actor/cause; không re-publish/re-verify timestamp | Replay payload chưa verify; stale worker commit |
| Retention Trimmer | Xóa theo `data.md §7` | Xóa outbox trên CDC watermark; xóa bảng audit |
| Dispute + Ledger modules | Dispute FSM/deadline, immutable balanced journals, balance/fee/payout reconciliation | Đổi Payment status theo dispute; mutable/unbalanced journal |
| PostgreSQL | Source of truth 12 bảng; một schema | Shared DB cho service khác |
| Redis | Hạ tầng tùy chọn cho accelerator tương lai; Gate 3 không đọc/ghi Redis | Source of truth; cấp quyền side effect |
| Debezium/Kafka Connect | WAL → `t.payment.events.v2` qua outbox SMT; chuyển `outbox_events.payload` full structured CloudEvent làm Kafka value và `partition_key` làm key | Được thay bằng polling worker; tự dựng envelope từ headers/data rời |
| KMS/Vault | Secret Stripe; key mã hóa `client_secret` | Lưu plaintext secret ở app |

## 3. Mô hình xử lý

### 3.1. Ba pipeline

| Pipeline | Vào | Ra | Đặc tính |
|---|---|---|---|
| Command (sync) | HTTP từ Order | Response + outbox | Idempotent theo key; TX1 → provider → TX2 (§3.4); p95 ≤ 1.5s |
| Fact (async) | Verified Stripe delivery → inbox/Kafka | Transition + journal + outbox | Inbox lease/fencing, dedup id, aggregate checkpoint; ack sau commit |
| Scheduled | Clock | Transition/operation/outbox | Batch nhỏ, `SKIP LOCKED`, lock theo payment, singleton khi cần |

Cả ba pipeline hội tụ ở **cùng use case và cùng FSM**; không có đường "nhanh" bỏ
qua guard cho bất kỳ pipeline nào.

### 3.2. Command pipeline

```mermaid
sequenceDiagram
    participant O as Order
    participant F as Idempotency filter
    participant U as Use case
    participant DB as PostgreSQL
    participant S as StripeAdapter
    O->>F: POST + Idempotency-Key + body
    F->>DB: payment_idempotency (key, action): COMPLETED? IN_PROGRESS? fingerprint?
    alt COMPLETED cùng fingerprint
        F-->>O: response đã lưu (regenerate client data nếu cần)
    else IN_PROGRESS và request khác còn giữ execution lock
        F-->>O: 409 REQUEST_IN_PROGRESS
    else INITIATE IN_PROGRESS không có active owner, recovery đã nhận operation
        F-->>O: 200 INITIATING (không có client data)
    else fingerprint khác
        F-->>O: 422 IDEMPOTENCY_KEY_REUSED
    else mới
        F->>DB: insert IN_PROGRESS
        U->>DB: TX1: aggregate + provider_operations REQUESTED + history (+ outbox nếu có fact)
        U->>S: call với provider_idempotency_key
        S-->>U: CONFIRMED | REJECTED | UNKNOWN
        U->>DB: TX2: bind kết quả + transition + history + outbox + idempotency COMPLETED
        U-->>O: response
    end
```

`UNKNOWN` (timeout/ambiguous) kết thúc ở TX2 với operation `UNKNOWN`; request đã quan
sát ambiguity nhận `503 retryable`, idempotency vẫn `IN_PROGRESS` tới khi recovery
bind được. Request đồng thời chỉ nhận `409` khi còn active execution owner; replay
initiate sau khi owner đã nhả lock và recovery đã nhận operation có thể nhận
`200 INITIATING` để phân biệt với contention.

### 3.3. Fact pipeline

1. Ingress: exact bytes + signature → verify trong 300s → encrypt/upsert inbox
   `RECEIVED` → publish verified internal CloudEvent → `200` sau Kafka ack.
2. Processor: check SHA-256 → atomic claim `PROCESSING` + fencing token → resolve
   object → provider checkpoint → guard amount/reference → FSM/dispute.
3. Cùng DB transaction: aggregate + history + balanced journal (nếu movement) +
   outbox + review case + inbox terminal; CAS đúng token → ack Kafka.
4. Lỗi tạm → `RETRY_WAIT`; DB Retry Worker claim trực tiếp. Hết retry → `DEAD`.

Async worker giữ ingress nhanh, nhưng authentication không trì hoãn qua cửa sổ 300s;
ADR-016 supersede phần verify-ở-worker của ADR-004. Durable inbox là replay source,
Kafka chỉ kích hoạt xử lý.

### 3.4. Pattern hai transaction quanh side effect provider

Mọi side effect Stripe (create/cancel/refund) tuân theo cùng một khung:

```
TX1  commit ProviderOperation{REQUESTED, provider_idempotency_key, fingerprint,
     safe_replay_until} + command execution lease/fencing
     (+ aggregate ở trạng thái "đang chờ provider": INITIATING / REFUNDING / ...)
---  ngoài transaction: gọi Stripe với đúng key đó; kết quả CONFIRMED | REJECTED | UNKNOWN
TX2  CAS fencing token; bind provider_object_id, transition FSM, history,
     balanced journal/outbox, operation status
```

Hệ quả kiến trúc:

- Không bao giờ giữ DB transaction mở trong lúc gọi Stripe (pool, lock).
- Crash giữa TX1 và TX2 để lại operation `REQUESTED`/`UNKNOWN`: trước safety horizon
  replay đúng key; sau horizon chỉ search/list/retrieve. Không bind duy nhất → review,
  tuyệt đối không blind-create/refund.
- Nếu bổ sung Redis lock sau này, nó chỉ giảm contention; **quyền** gọi thuộc DB lease/fencing của operation
  ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
- Mọi terminal non-capture (`T3`/`T4`/`T5`) là một `CANCEL` operation đã
  `PROVIDER_CONFIRMED` — Order nhận `PAYMENT_FAILED/EXPIRED/CANCELED` là biết intent
  không thể capture ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).

### 3.5. Mapping Stripe event → FSM

| Stripe event | Transition | Financial fact |
|---|---|---|
| `payment_intent.processing` | `T1` | — |
| `payment_intent.succeeded` | `T2` | `PAYMENT_SUCCESS` |
| `payment_intent.payment_failed` | `T3a` (retryable) hoặc `CANCEL` op → `T3` (non-retryable/quá ngưỡng) | — / `PAYMENT_FAILED` |
| `payment_intent.canceled` | `T3`/`T4`/`T5` theo `CANCEL` operation đã ghi; thường no-op | `PAYMENT_FAILED`/`PAYMENT_EXPIRED`/`PAYMENT_CANCELED` nếu chưa áp |
| `charge.refunded` | Tín hiệu: `Refund.retrieve` rồi áp `Refund.status` ([ADR-022](./decisions/ADR-022-refund-lifecycle-hardening.md)) | theo kết quả |
| `refund.updated` (`status=succeeded`) | `R2` + `T7` | `REFUND_SUCCESS` |
| `refund.updated` / `refund.failed` (`status` ∈ {`failed`, `canceled`}) | `R3` + `T8` (đang `PROCESSING`) hoặc `R5` + `T9` (đã `SUCCESS`; thêm `requires_action`) | `REFUND_FAILED` / `REFUND_REVERSED` |
| `charge.dispute.created/updated/closed` | `D1`/`D2`/`D5`; Payment FSM không đổi | `DISPUTE_OPENED` / `DISPUTE_WON` / `DISPUTE_LOST` khi tương ứng |
| `charge.dispute.funds_withdrawn/reinstated` | `D3`/`D4` + balanced journal | `DISPUTE_FUNDS_WITHDRAWN` / `DISPUTE_FUNDS_REINSTATED` |
| khác | `IGNORED` | — |

Chi tiết guard và precedence: `domain.md §6.4`, `interfaces.md §4`.

## 4. Mô hình nhất quán

| Phạm vi | Mô hình | Cơ chế | Ai là nguồn thật |
|---|---|---|---|
| Payment/Refund state + audit + outbox | Strong, local ACID | Một transaction cho mỗi transition | PostgreSQL Payment |
| Side effect Stripe ↔ DB | Eventual, có recovery | operation + DB lease/fencing + bounded replay + lookup/reconciliation | Stripe cho trạng thái provider; Payment cho trạng thái nghiệp vụ |
| Provider balance ↔ subledger | Eventual, immutable | unique balance transaction + overlap cursor + balanced journals | Stripe balance / Payment operational audit |
| Stripe ↔ DB (drift) | Eventual, có correction | Webhook → poller → reconciliation hourly → `CORR` (khi không freeze) → review case | Stripe |
| Payment → Order | Eventual, at-least-once, ordered theo `order_id` | Outbox CDC; `financial_state_version` monotonic per transaction | Payment |
| Order đọc trực tiếp | Read-your-writes | `GET /v1/payments/{id}` đọc primary sau commit | Payment |
| Client data | Strong tại Payment | Mã hóa at rest; `usable` tính tại thời điểm đọc | Payment (Stripe giữ bản gốc) |
| Redis | Không nằm trên runtime path Gate 3 | Package adapter được giữ trống; thêm accelerator phải có test outage/fallback | — |
| Review case | Strong | Cùng transaction với transition mở case | Payment |

`financial_state_version` là **contract nhất quán** với Order: mọi fact của một
payment transaction mang version tăng đúng 1; Order lưu `last_financial_state_version`
và bỏ qua fact có version ≤ đã thấy. Partition order Kafka chỉ là tối ưu, không phải
guard.

## 5. Mô hình đồng thời và ordering

Bốn lớp guard, từ rẻ đến đắt, áp theo thứ tự:

1. **Unique constraint** (`(order_id, attempt)`, `provider_transaction_id`,
   `refund.order_id`, `provider_idempotency_key`, `stripe_event_id`, dispute/balance
   transaction/source, partial unique `CREATE`/`CANCEL`/`REFUND`, một review case mở/payment) — guard cuối, không
   phụ thuộc Redis.
2. **Optimistic lock `version`** trên aggregate — hai writer (webhook vs poller vs
   scanner) không ghi đè nhau; thua → retry/requeue
   ([ADR-006](./decisions/ADR-006-optimistic-lock-and-redis-lock-strategy.md)).
3. **Provider checkpoint** `(event_created_at,event_id)` riêng trên payment/refund/
   dispute; tie xung đột retrieve provider object. Lifecycle fact chứng minh được
   thì áp; tie dispute funds movement không thể suy ra từ object lifecycle phải mở
   review và để balance-ledger reconciliation đọc balance transaction.
4. **DB lease/fencing** trên command/inbox ngăn stale worker complete.
5. **Deferred ledger balance constraint** rollback journal không cân bằng.
6. **Không có Redis correctness path**; runtime hiện dựa hoàn toàn vào DB. Accelerator
   Redis tương lai chỉ được phép giảm contention.

Ordering:

- **Kafka verified webhook:** key `STRIPE:<stripe_event_id>`; đúng đắn dựa vào inbox
  lease, aggregate lock và provider checkpoint, không dựa vào partition order.
- **Kafka fact out:** key `order_id` → mọi `PAYMENT_*`/`REFUND_*` của một order cùng
  partition ([ADR-007](./decisions/ADR-007-order-id-partition-key.md)); Order vẫn
  guard bằng `financial_state_version`.
- **Stripe:** idempotency key theo operation, không theo request; retry với cùng
  key + cùng payload; key với payload khác bị Stripe từ chối (`idempotency_error`)
  → operation `PROVIDER_REJECTED` + review case `PROVIDER_CREATE_REJECTED`; Payment
  giữ `INITIATING` và command hoàn tất bằng stored non-retryable error.
- **Scheduler nhiều instance:** `SELECT … FOR UPDATE SKIP LOCKED` trên batch +
  lock theo payment; reconciliation/trimmer singleton.

CREATE có ngoại lệ certified first-dispatch `400 amount_too_small` theo
[ADR-024](./decisions/ADR-024-certified-create-non-acceptance.md): durable `422`
proof cho phép Order bù giữ chỗ. Không tạo financial transition giả, không mở review
case và không đổi cách xử lý UNKNOWN, idempotency error hoặc receipt `503` đã tồn tại.

## 6. Mô hình triển khai

### 6.1. Process

| Process | Thành phần | Scale | Readiness |
|---|---|---|---|
| `payment-api` | HTTP v1 + verified webhook ingress | Ngang, 2+ | PostgreSQL + KMS + Kafka producer; webhook ready chỉ khi durable path healthy |
| `payment-worker` | Webhook Processor + tất cả scheduler | Ngang tới số partition raw (6); scheduler dùng lock | PostgreSQL + Kafka consumer + Stripe key |

Có thể gộp hai process một image, tách bằng profile; tách process để scale và
deploy độc lập (worker restart không làm rớt webhook endpoint).

### 6.2. Mạng và policy

- Mesh `AuthorizationPolicy` cho `payment-service` cấp principal
  `ecommerce/order-service` đúng sáu operation Order-facing: `POST initiate/cancel/refund`
  và `GET payment/client-data/refund`; không dùng wildcard `POST /v1/payments/**` vì
  wildcard đó sẽ bao trùm webhook. Riêng `POST /v1/payments/webhook/stripe` chỉ từ
  `edge/traefik`; metrics từ `observability/prometheus` (bảng ở
  `docs/mesh/runtime-contract.md`).
- Mesh timeout `POST /v1/payments/**` 15s, **không retry** (caller sở hữu retry bằng
  idempotency key); gateway chờ header webhook 20s, không retry.
- Egress Stripe qua `ServiceEntry`; không egress nào khác.
- Gateway: chỉ `POST /api/payments/webhook/stripe` (`stripe-webhook`: bypass JWT,
  giữ raw body, rate limit theo IP), rewrite upstream sang
  `/v1/payments/webhook/stripe`; Payment không có route buyer.

### 6.3. Hạ tầng

PostgreSQL primary (+ replica cho `GET` dashboard/vận hành), Redis, Kafka (2 topic),
Debezium connector, Vault/KMS. Cấu hình và giá trị ở `operations.md §2`; dung lượng
và giả định tải ở `operations.md §6`.

## 7. Ràng buộc kiến trúc (kiểm tra được)

| # | Ràng buộc | Kiểm tra |
|---|---|---|
| A1 | Chỉ package `com.platform.paymentservice.stripe` import `com.stripe.*` | ArchUnit |
| A2 | `domain` không phụ thuộc Spring/JPA/Stripe; `application` không phụ thuộc `adapter` | ArchUnit |
| A3 | Không gọi `PaymentProviderPort` bên trong `@Transactional` | ArchUnit + test: adapter ném lỗi nếu có transaction đang mở |
| A4 | Mọi `PaymentProviderPort` call phải nhận `ProviderOperation` đã persist | Chữ ký port yêu cầu `operationId`; test |
| A5 | Không transition nào không insert `payment_status_history` + (khi là fact) `outbox_events` trong cùng transaction | Test FSM listener; assertion đếm row |
| A6 | `outbox_events` không có cột polling-style; `payload` là full structured CloudEvent; không producer Kafka nào ghi `t.payment.events.v2` ngoài Debezium | Migration lint; schema/connector integration test; ACL topic |
| A7 | Webhook endpoint không import repository/StripeAdapter | ArchUnit |
| A8 | Không service nào ghi DB Payment; Payment không đọc DB service khác; không consumer trên `t.order.events` | Network policy; code review |
| A9 | `client_secret` plaintext chỉ tồn tại trong response initiate/client-data | Test log/outbox không chứa `_secret_` |
| A10 | Terminal non-capture chỉ qua `CANCEL` operation `PROVIDER_CONFIRMED` hoặc webhook `canceled` đã verify | Test FSM guard (`INV-PAY-010`) |
| A11 | Refund chỉ từ HTTP command có `Idempotency-Key` + `inventory_evidence` | Contract test; không có consumer Order event |
| A12 | Refund/dispute/ledger là module rõ nhưng vẫn thuộc Payment; tách service cần ADR mới, không tự động theo traffic | ArchUnit slice ([ADR-002](./decisions/ADR-002-refund-inside-payment-service.md), ADR-019) |
| A13 | Một refund single-shot trọn đời/order; không partial quantity/incremental | Unique `refund.order_id` + scope validation |
| A14 | Payload chưa verify không vào DB/Kafka; retry không re-verify timestamp | Ingress/inbox integration test |
| A15 | Worker stale không commit; provider call sau horizon không blind-retry | Lease/fencing + clock-controlled recovery test |
| A16 | Mỗi provider aggregate có ordering checkpoint | Out-of-order/tie/retrieve tests |
| A17 | Mọi money movement có immutable balanced journal | Deferred DB constraint + source uniqueness |
| A18 | Dispute orthogonal với Payment FSM và phát 5 facts | FSM + consumer contract tests |

## 8. Đánh đổi, phụ thuộc chéo và ADR

**Đánh đổi chính:**

- Hai transaction + operation table cho mỗi side effect: thêm một bảng và một
  worker, đổi lại không có side effect mồ côi và không suy luận non-capture.
- Cancel intent trước `EXPIRED`/`FAILED`: thêm một round-trip Stripe, đổi lại đóng
  race capture muộn và giữ contract Order đơn giản.
- Async webhook: thêm topic + độ trễ ~giây, đổi lại không mất fact khi DB down.
- Một OLTP schema: bảng tăng đơn điệu, đổi lại uniqueness/FK enforce được ở DB.
- Redis chỉ best-effort: thêm code fallback, đổi lại Redis không nằm trên đường
  đúng đắn.
- Stripe lock-in: đổi lại bỏ factory/mô hình đa provider; narrow port chỉ cô lập SDK,
  không hứa khả năng thay provider trong suốt.

**Phụ thuộc chéo đã đồng bộ ngoài `docs/payment`:** `docs/mesh/runtime-contract.md`
(AuthorizationPolicy `GET`), `docs/gateway/runtime-contract.md` (chỉ route webhook),
`contracts/catalog/interactions.yaml`, `docs/order/data.md`.

**ADR liên quan:** [ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md) runtime;
[ADR-002](./decisions/ADR-002-refund-inside-payment-service.md) refund trong Payment;
[ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md) Stripe duy nhất;
[ADR-004](./decisions/ADR-004-async-webhook-processing-via-kafka.md) webhook async;
[ADR-005](./decisions/ADR-005-transactional-outbox-cdc.md) outbox CDC;
[ADR-006](./decisions/ADR-006-optimistic-lock-and-redis-lock-strategy.md) lock;
[ADR-007](./decisions/ADR-007-order-id-partition-key.md) partition key;
[ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md) payload tối thiểu;
[ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md),
[ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md) refund command/one-lifetime;
[ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md) provider operation;
[ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md) một schema;
[ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md) decline;
[ADR-016](./decisions/ADR-016-authenticated-webhook-inbox.md) verified inbox;
[ADR-017](./decisions/ADR-017-provider-idempotency-horizon-and-command-leases.md) horizon/leases;
[ADR-018](./decisions/ADR-018-provider-event-ordering-and-stripe-contract.md) ordering;
[ADR-019](./decisions/ADR-019-payment-owned-subledger-and-disputes.md) ledger/disputes;
[ADR-020](./decisions/ADR-020-financial-retention-privacy-and-disaster-recovery.md) retention/DR.
