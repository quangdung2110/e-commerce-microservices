---
doc_id: payment-development
service: payment
scope: target
status: implemented
owners:
  - Payment Service team
evidence:
  - java/services/payment-service/pom.xml
  - java/services/payment-service/src/main/java/com/platform/paymentservice
  - java/services/payment-service/src/test/java/com/platform/paymentservice
  - java/services/payment-service/src/main/resources/db/migration/V1__payment_baseline.sql
  - java/services/payment-service/src/main/resources/kafka/debezium-connector-payment-outbox.json
  - java/services/payment-service/scripts/validate-payment-gate2.ps1
verified_against_commit: null
---

# Payment Service — Hướng dẫn phát triển

> **Đối chiếu runtime 2026-10-11:** xem [phạm vi hiện tại và năng lực chưa triển khai](./runtime-status.md)
> và [ADR-023](./decisions/ADR-023-reconciliation-ownership-and-audit-corrections.md). Các mục tiêu KMS, metric nghiệp vụ,
> heartbeat, health/DR/export và RBAC bên ngoài không phải bằng chứng đã được runtime hiện thực.

> Checklist để bắt đầu implementation `java/services/payment-service` theo đúng
> quy ước của các Java service đã có trong repo (`promotion-service`,
> `review-service`, …) và theo thiết kế trong bộ tài liệu này. Gate 3 đã hiện thực
> runtime, FSM, persistence/idempotency, Stripe adapter, webhook pipeline, toàn bộ
> recovery/reconciliation/retention worker và Finance Ops review/correction API;
> trạng thái cùng bằng chứng lệnh nằm ở `implementation-gates.md`.
> Dữ liệu Stripe luôn là **test
> mode**; không có bước nào dùng live key.

## 1. Điều kiện tiên quyết

| Thành phần | Yêu cầu | Ghi chú |
|---|---|---|
| Java / build | JDK 21, Maven 3.9, Spring Boot 3.3.x (parent như `promotion-service/pom.xml`) | Máy dev không cần JDK: build bằng container `maven:3.9-eclipse-temurin-21` (§2) |
| Container runtime | Docker Engine + Compose v2 | `docker-compose.yml` ở gốc repo cung cấp PostgreSQL, Redis, Kafka, Kafka Connect (Debezium 3.0) |
| Stripe | Tài khoản Stripe test mode, một **Sandbox riêng** cho mỗi dev; Stripe CLI ≥ 1.19 | `stripe login` với sandbox; không dùng chung `whsec_` giữa các máy |
| Secret local | `sk_test_…`, `pk_test_…`, `whsec_…` từ Stripe CLI, một local AES-256 key cố định (chưa có KMS adapter) | Root `.env.local` đã gitignore; Compose cần `--env-file .env.local`; mẫu `.env.checkout.example` |
| Công cụ test | JUnit 5, Testcontainers PostgreSQL và các fake port có trạng thái trong JVM | Chi tiết §6; WireMock/stripe-mock/Toxiproxy chưa được cấu hình trong module |
| Contract tooling | Node.js ≥ 24.11, npm, Redocly CLI 2.53.3, AsyncAPI CLI 6.1.0 | Pin trong `contracts/tools/package-lock.json`; §7 |

Dependency Maven bắt buộc (khớp ADR-001): `spring-boot-starter-web`, `-validation`,
`-data-jpa`, `-jdbc`, `-actuator`,
`spring-kafka`, `flyway-core` + `flyway-database-postgresql`, `postgresql`,
`spring-retry`, `com.stripe:stripe-java:33.4.2` (API `2026-08-26.dahlia`, ADR-021),
`micrometer-registry-prometheus`, `archunit-junit5` (test),
`spring-boot-starter-test`, `spring-kafka-test` và Testcontainers PostgreSQL.

## 2. Chạy local

### 2.1. Hạ tầng

```bash
docker compose up -d postgres redis kafka kafka-connect
```

`init-db.sh` đã tạo `payment_service`. Nếu volume PostgreSQL có từ trước thay đổi
này, tạo riêng DB một lần thủ công, giữ dữ liệu volume hiện hữu. Payment dùng port
`8089:8080` và profile Compose opt-in `payment` để không buộc mọi dev phải có Stripe
sandbox secrets.

### 2.2. Build

```bash
docker run --rm -v "$PWD:/repo" -v payment-mvn-repo:/root/.m2 \
  -w /repo/java/services/payment-service \
  maven:3.9-eclipse-temurin-21 mvn -q -B -DskipTests package
```

### 2.3. Chạy service

```bash
SPRING_PROFILES_ACTIVE=dev java -jar java/services/payment-service/target/payment-service-*.jar
```

hoặc:

```bash
docker compose --env-file .env.local --profile payment up -d --build payment-service
```

Compose build từ `java/services/payment-service/Dockerfile`, chạy profile `dev`,
healthcheck `/actuator/health/liveness`. Các giá trị `*_local_change_me` chỉ giúp
container khởi động; mọi call Stripe thực phải override bằng test-mode sandbox key.

### 2.4. Webhook local

```bash
stripe listen --forward-to localhost:8089/v1/payments/webhook/stripe \
  --events payment_intent.processing,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,refund.updated,refund.failed,charge.dispute.created,charge.dispute.updated,charge.dispute.closed,charge.dispute.funds_withdrawn,charge.dispute.funds_reinstated
```

Lệnh in ra `whsec_…` — đặt vào `PAYMENT_STRIPE_WEBHOOK_SECRETS` rồi khởi động lại
container bằng `docker compose --env-file .env.local --profile payment up -d --force-recreate payment-service`.
Runtime hiện tại đọc env lúc startup; không có hot reload secret. Listener phải cùng
Sandbox với `sk_test_`/`pk_test_`. Có thể forward qua Gateway `localhost:8088` cùng path;
frontend 3000 không rewrite webhook. Không đăng ký endpoint local trên Stripe Dashboard.

### 2.5. Debezium connector

Artifact `src/main/resources/kafka/debezium-connector-payment-outbox.json` (theo mẫu
của `promotion-service`), đăng ký:

```powershell
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services payment
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services payment -CheckOnly
```

Chạy từ gốc repo. Kafka Connect không tự thay `${VAR:default}` trong template;
script thay DB fields và giữ literal EventRouter expression. Root default là
`postgres:5432/payment_service`; custom credential truyền bằng tham số, không log
resolved config. Connector và mọi task phải RUNNING. Đăng ký trước đơn mới vì
`snapshot.mode=no_data` không snapshot outbox cũ khi khởi tạo connector.

Connector: `table.include.list=public.outbox_events`, outbox `EventRouter` với
`route.by.field=aggregate_type` → `t.payment.events.v2`, key từ `partition_key`,
`expand.json.payload=true`; `payload` đã là full CloudEvents structured JSON và phải
đến Kafka value nguyên vẹn. Header từ cột `headers` chỉ mirror ID/type/correlation/
trace cho observability, không được dùng để dựng lại hoặc hiểu event.

### 2.6. Kiểm tra và dọn dẹp

- Actuator `/actuator/health/liveness` và `/actuator/health/readiness` là probes hiện có.
  Chưa có custom probe chứng minh Stripe credential/CDC/Kafka assignment; `UP` không
  chứng minh thanh toán được. `/internal/health/deps` là mục tiêu chưa hiện thực.
- `/v1/payments/initiate` là command service-to-service, cần identity/correlation,
  idempotency và body hợp lệ. Frontend đặt đơn qua Checkout, đọc credentials qua Order.
- `stripe trigger payment_intent.succeeded` smoke transport với fixture riêng;
  không chứng minh PaymentIntent của đơn Checkout. E2E phải thanh toán đơn từ UI và
  đối chiếu webhook → Payment fact → Order PAID → Inventory/Cart theo
  [checkout runbook](../frontend/checkout-local-runbook.md).
- Dọn: dừng service đã bật để test bằng `docker compose stop <service...>`;
  giữ volume. Dừng `stripe listen` bằng Ctrl-C.

## 3. Dependency local

| Dependency | Compose service / port | Khởi tạo | Mock được? |
|---|---|---|---|
| PostgreSQL 16 | `postgres` / 15432 | DB `payment_service`; Flyway tự chạy migration khi start | Không (Testcontainers ở test) |
| Redis 7 | `redis` / 6379 | Payment Gate 3 chưa có adapter đọc/ghi; Compose vẫn cung cấp cho hệ thống | Không cần mock vì không có Payment code path phụ thuộc Redis |
| Kafka | `kafka` / 9092 (nội bộ), 29092 (host, chỉ 127.0.0.1) | Broker tắt auto-create ([mesh ADR-002](../mesh/decisions/ADR-002-local-runtime-exposure-and-broker-auth.md)); `kafka-init` tạo topic trong `infra/kafka/topics.txt`; SASL/SCRAM + ACL ([mesh ADR-003](../mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)): principal `payment-service` chỉ đọc/ghi `t.payment.webhooks.raw`, còn `t.payment.events.v2` chỉ `debezium-payment` ghi được; staging/prod phải provision `t.payment.webhooks.raw` và `t.payment.events.v2` đúng policy trong `operations.md` | Listener/publisher boundary có unit test; không dùng Kafka Testcontainer hiện tại |
| Kafka Connect (Debezium) | `kafka-connect` / 8084 | Đăng ký connector §2.5 | Có — bỏ qua khi chỉ test API; outbox vẫn được ghi |
| Stripe | Internet, test mode | Sandbox riêng + Stripe CLI | Có — narrow `StripeGateway` fake dùng SDK model thật (§6) |
| Order | Không cần | Gọi Payment bằng `curl`/fixture với `X-Service-Identity: order-service` | — |
| KMS | Không cần | Profile `dev` dùng `payment.crypto.local-key` (AES-256 base64 cố định trong `.env.local`) | — |

## 4. Cấu hình local

Property theo catalog `payment.*` ở `operations.md §2`; Spring relaxed binding cho
env var (`payment.stripe.secret-key` ↔ `PAYMENT_STRIPE_SECRET_KEY`).

| Env var (dev) | Property | Bắt buộc | Ví dụ dev |
|---|---|---|---|
| `PAYMENT_STRIPE_SECRET_KEY` | `payment.stripe.secret-key` | Có | `sk_test_…` |
| `PAYMENT_STRIPE_PUBLISHABLE_KEY` | `payment.stripe.publishable-key` | Có | `pk_test_…` |
| `PAYMENT_STRIPE_WEBHOOK_SECRETS` | `payment.stripe.webhook-secrets` | Có | `whsec_…` (từ `stripe listen`) |
| `PAYMENT_STRIPE_API_VERSION` | `payment.stripe.api-version` | Có | `2026-08-26.dahlia`, giống mọi môi trường |
| `PAYMENT_CRYPTO_LOCAL_KEY` | `payment.crypto.local-key` (chỉ profile `dev`/`test`) | Có | base64 32 byte |
| `PAYMENT_SESSION_TTL` | `payment.session.ttl` | Không | `PT15M`; test nhanh: `PT2M` |
| `PAYMENT_STRIPE_DECLINE_MAX_PER_ATTEMPT` | `payment.stripe.decline.max-per-attempt` | Không | `5` |
| `PAYMENT_SCHEDULER_*` | Chưa có env alias tổng quát | Không | Override bằng Spring property, ví dụ `PAYMENT_SCHEDULER_EXPIRATION_INTERVAL=PT10S` |
| `PAYMENT_DATABASE_URL` | `spring.datasource.url` | Có | `jdbc:postgresql://localhost:15432/payment_service` |
| `PAYMENT_KAFKA_BROKERS` | `spring.kafka.bootstrap-servers` | Có | `localhost:29092` |
| `SPRING_KAFKA_SECURITY_PROTOCOL`, `SPRING_KAFKA_PROPERTIES_SASL_*`, `KAFKA_SASL_USERNAME`/`KAFKA_SASL_PASSWORD` | `spring.kafka.security.protocol`, `spring.kafka.properties.sasl.*` | Có với broker compose | Principal `payment-service`; compose đặt qua `x-kafka-sasl-java` ([mesh ADR-003](../mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)) |
| `PAYMENT_REDIS_HOST` | `spring.data.redis.host` | Không | `localhost` (hạ tầng dự trù) |

Quy tắc: `application.yml` chỉ chứa default không nhạy cảm và placeholder `${…}`;
module hiện chỉ có `application.yml`; profile `dev` cho phép local AES key nhưng
không tự nạp secret. Không có giá trị secret commit; `.env.local` gitignored. CI fail nếu grep thấy `sk_live_`,
`pk_live_`, `whsec_` literal trong repo. Profile `test` không bao giờ đọc env Stripe
thật.

## 5. Build, lint và format

| Bước | Command mục tiêu | Pass khi |
|---|---|---|
| Compile + unit | `mvn -B test` | 0 failure; ArchUnit rules A1–A18 (`architecture.md §7`) pass |
| Integration | `mvn -B verify` (Failsafe `*IT`) | Testcontainers PostgreSQL 16; xem §6.3 về Docker API version |
| Format/lint | Chưa cấu hình Spotless/Checkstyle trong module | `git diff --check` và compiler pass; không tuyên bố formatter gate chưa có |
| Migration/contract consistency | `scripts/validate-payment-gate2.ps1` | 12 bảng, safety trigger/index và operation/event mapping đúng |
| Contract lint/bundle | `cd contracts/tools && npm ci && npm run verify:payment` | Redocly + AsyncAPI validate và bundle 4 contract (§7) |
| Docs | `validate_docs.ps1` (đã bao gồm `docs/payment`) + link check | 0 finding |
| Secret scan | grep `sk_live_|pk_live_|whsec_[A-Za-z0-9]` ngoài `.env.local` | 0 match |

CI chạy đúng các command này; local dùng cùng command qua container maven.

## 6. Kiểm thử

### 6.1. Chiến lược theo tầng

Stripe là dependency ngoài duy nhất của Payment ([ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)),
nên chiến lược test xoay quanh câu hỏi: **mỗi tầng test "nói chuyện" với Stripe
bằng hình thái nào?** Càng lên tầng cao, độ trung thực càng tăng nhưng tốc độ,
tính xác định và khả năng chạy offline càng giảm.

| Tầng | Hình thái Stripe | Hạ tầng | Khi nào chạy | Command |
|---|---|---|---|---|
| Unit | Không gọi Stripe. `PaymentProviderPort` fake; webhook payload là fixture tự ký bằng `whsec_` giả | Không | Mọi commit, offline | `mvn test` |
| Integration | `StripeGateway` fake điều khiển response/timeout + SDK model thật; PostgreSQL 16 thật | Testcontainers PostgreSQL | Mọi PR, offline | `mvn verify` |
| E2E | Stripe **test mode** (sandbox riêng) + Stripe CLI `listen`/`trigger` + test card | Compose local hoặc staging | Thủ công trước release; chỉ chạy khi có test secrets | Quy trình §6.4; không có profile Maven giả lập sandbox |
| Failure injection | Fake gateway ném timeout/4xx/5xx và trả ambiguous result | JVM + PostgreSQL Testcontainers | Mọi PR cho correctness path | Các test `StripePaymentProviderAdapterTest`, `ProviderRecoveryIT` |

**Ranh giới giữa các hình thái Stripe.**

- Offline suite hiện dùng fake `StripeGateway`/provider port có trạng thái; fake giữ
  cùng provider object cho cùng idempotency key và chủ động trả timeout/4xx/5xx để
  kiểm tra recovery. Module không cấu hình WireMock, `stripe-mock` hay Toxiproxy.
- Ambiguous transport result (timeout sau khi provider đã tạo object) được fake
  mô phỏng có chủ đích. Sandbox test mode chỉ xác minh tích hợp thật, không thay thế
  fault test xác định này ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
- `Webhook.constructEvent` là hàm thuần (HMAC-SHA256 trên `t.payload`), test offline
  hoàn toàn: ký fixture bằng `whsec_` giả, biến thể `t` ngoài cửa sổ 300s, `v1` sai,
  payload bị sửa sau khi ký, hai secret trong danh sách rotation.

**Môi trường Stripe.**

- Mỗi môi trường một Stripe Sandbox riêng (dev-local mỗi người một sandbox,
  `staging`, `ci`). Không dùng chung `sk_test_`/`whsec_` giữa môi trường để tránh
  webhook của người này bắn vào máy người kia.
- Không tầng test nào được dùng live key; CI fail nếu phát hiện `sk_live_`/`pk_live_`.
- Ràng buộc đã biết: Stripe **live** chưa hỗ trợ merchant đăng ký tại Việt Nam; test
  mode không bị giới hạn. Toàn bộ vòng đời dev/demo chạy trên test mode; go-live cần
  pháp nhân ở quốc gia Stripe hỗ trợ (`README.md §10`).

**Test card ↔ trạng thái đích** (tầng E2E; số card theo tài liệu Stripe, kiểm tra
lại khi Stripe đổi).

| Test card | Kết quả Stripe | Event webhook | Trạng thái Payment đích |
|---|---|---|---|
| `4242 4242 4242 4242` | Thành công ngay | `payment_intent.succeeded` | `SUCCESS` |
| `4000 0025 0000 3155` | Yêu cầu 3D Secure | `requires_action` → sau xác thực `payment_intent.succeeded` | `PENDING` → `SUCCESS` |
| `4000 0000 0000 0002` | `card_declined` (`generic_decline`, retryable) | `payment_intent.payment_failed` | `T3a`: vẫn `PENDING`, `decline_count=1`; thanh toán lại bằng `4242…` → `SUCCESS` |
| `4000 0000 0000 9995` | `insufficient_funds` (retryable) | `payment_intent.payment_failed` | `T3a`: vẫn `PENDING` |
| `4000 0000 0000 9979` | `stolen_card` (non-retryable) | `payment_failed` → Payment `PaymentIntent.cancel` → `payment_intent.canceled` | `T3` `FAILED`, `PAYMENT_FAILED error_code=STOLEN_CARD` |
| `4000 0000 0000 0002` × 6 lần | Quá `max_declines_per_attempt` | như trên | `T3` `FAILED`, `error_code=DECLINE_LIMIT` |
| `4000 0000 0000 0259` | Thành công rồi bị dispute | dispute lifecycle/funds events | Payment giữ `SUCCESS`; dispute FSM + journal + facts |
| `4000 0000 0000 5126` | Thanh toán thành công, refund thất bại async | `refund.updated`/`refund.failed` | `R3`/`T8` hoặc `R5`/`T9` + review case |
| Không thanh toán trong `payment_session_ttl` | — | Scanner `PaymentIntent.cancel` → `payment_intent.canceled` | `T4` `EXPIRED` (chỉ sau khi intent canceled) |

**Kịch bản webhook không tạo được bằng thanh toán thường.**

- Duplicate: `stripe trigger <event>` hai lần, hoặc "Resend" → terminal inbox ack;
  non-terminal inbox vẫn được DB Retry Worker tiếp tục.
- Out-of-order (`payment_failed` đến sau `succeeded`): Stripe CLI không kiểm soát thứ
  tự; replay fixture đã ký thẳng vào `POST /v1/payments/webhook/stripe` theo thứ tự
  chủ đích (tầng integration).
- Webhook miss: tắt `stripe listen`, thanh toán bằng test card, kiểm tra
  Reconciliation (payment) hoặc Refund Poller (refund) chốt trạng thái từ
  `PaymentIntent.retrieve`/`Refund.retrieve`; bật lại `listen` để xác nhận webhook
  đến muộn bị bỏ qua idempotent.
- Đổi `whsec_`: giữ secret cũ+mới overlap, recreate/restart app rồi rotate; delivery đã bị
  `400` phải Resend để có signature/timestamp mới, không re-verify payload cũ.
- Retry 3–5 lần của Stripe: endpoint trả `503` có chủ đích (feature flag test) →
  quan sát Stripe retry và xác nhận không xử lý trùng.

**Thời gian.** Stripe Test Clocks chỉ áp dụng cho Billing/subscription, không đẩy
nhanh timeout của PaymentIntent. Mọi scheduler nhận `java.time.Clock` (`ClockPort`)
để unit/integration test tua thời gian; tầng E2E dùng profile với interval rút
ngắn (`payment.session.ttl=PT2M`, `expiration.interval=PT10S`) thay vì chờ thật.

**Cổng CI.**

- Mỗi PR: unit + integration (offline, fake gateway + Testcontainers); không
  cần secret Stripe.
- Nightly trên `main` / trước release: E2E test mode với key sandbox `ci` từ CI secret
  store; failure injection đầy đủ.
- Bất kỳ transition nào trong bảng FSM (`domain.md §6`) chưa có test ở tầng unit →
  CI fail (test liệt kê transition đã cover so với enum).

### 6.2. Case bắt buộc theo tầng

**Unit (domain + application với port fake):**

- Payment FSM `T0`–`T9`, Refund FSM `R1`–`R5`, Dispute FSM `D1`–`D5`, `CORR`,
  invalid transition và provider checkpoint stale/tie/retrieve.
- Policy decline (`RULE-PAY-014`): retryable → `PENDING`; non-retryable/quá ngưỡng →
  `CANCEL` operation → `FAILED`; cancel trả `succeeded` → `T2`.
- `financial_state_version` +1 đúng một lần cho mỗi outbox event, không tăng ở `T3a`.
- Guard: ingress signature (đúng/sai/ngoài 300s/secret thứ hai/body >256 KiB), amount/reference mismatch,
  idempotency fingerprint (`IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`), đặc biệt
  cancel/refund dùng cùng key + body trên `transactionId` khác phải bị `422`.
- Refund: one-lifetime single-shot per `order_id`, `amount ≤ amount_minor`, evidence
  bắt buộc, mỗi selected line full quantity; reject partial/incremental/second refund.
- Mapping Stripe event → transition theo `interfaces.md §4`; `refund.updated`
  `status=succeeded` → `R2`/`T7`, `failed`/`canceled` → `R3`/`T8` hay `R5`/`T9` tùy trạng
  thái refund, `charge.refunded` khi refund còn `pending` không chốt `SUCCESS`
  ([ADR-022](./decisions/ADR-022-refund-lifecycle-hardening.md)); `payment_intent.canceled`
  → terminal theo `CANCEL` operation đã ghi (hoặc `PROVIDER_CANCELED` khi không có).
- `client-data`: `usable` theo `status`/`client_expires_at`; ciphertext bị xóa khi rời
  `PENDING`; key rotation giải mã được key cũ.
- Provider horizon + command/inbox lease/fencing bằng ClockPort; stale token reject.
- Journal mapping mọi movement và debit=credit; dispute không đổi Payment FSM.
- ArchUnit A1–A18.

**Integration (Testcontainers):**

- PostgreSQL: transition + history + journal + outbox + inbox terminal atomic;
  optimistic conflict; duplicate terminal ack nhưng `RETRY_WAIT` không bị nuốt; lease
  takeover và stale fencing reject; hai `payment_failed` khác `evt_` đều được ghi; partial
  unique một `CANCEL`/payment khi scanner và Order cancel đồng thời; unique
  `refund_transactions.order_id` khi hai refund khác key; partial unique một review
  case mở/payment; unique provider balance/source; deferred unbalanced journal rollback.
- PostgreSQL idempotency/lease: replay, fingerprint mismatch, concurrent claim,
  crash takeover và stale fencing; Gate 3 không có Redis code path.
- Debezium: outbox → `t.payment.events.v2` at-least-once, key đúng
  `data.order_id`, Kafka value là full structured CloudEvent; header mirror không
  phải authority; connector restart không mất và duplicate giữ cùng `(source,id)`.
- Stripe fake có trạng thái: create/cancel/refund với Payment-namespaced key trả cùng
  object; ambiguous transport (timeout sau khi tạo) → operation `UNKNOWN` → recovery
  bind; `idempotency_error` → `PROVIDER_REJECTED` + review case
  `PROVIDER_CREATE_REJECTED` + stored `503` non-retryable; `Refund.create` với
  key; trước 23h retry same-key, sau horizon lookup-only; zero/multiple → review;
  Refund/Dispute/BalanceTransaction retrieve/list mọi trạng thái.
- Scheduler: scanner cancel actual `PENDING/PROCESSING`; không đụng `INITIATING`;
  cancel trả `succeeded` → `SUCCESS`; poller chốt khi webhook miss; recovery replay
  cùng key và bind `pi_`/`re_`; reconciliation `CORR` idempotent và chỉ mở review case
  khi freeze; balance lane cursor overlap; trimmer chỉ xóa dưới confirmed slot LSN.
- Review case: `requires_manual_review` đồng bộ với case; đóng thiếu
  `resolution_type` bị reject; correction `MANUAL` ghi `actor`/`cause_ref`.

**Contract/provider/consumer:**

- Provider test: implementation ↔ public OpenAPI (7 operation), internal admin
  OpenAPI và ↔ financial/internal AsyncAPI. Cả 15 exact namespaced
  type phải khớp `dataschema`; `expires_at` chỉ ở `PAYMENT_INITIATED`; `error_code`/
  `reason_code` bắt buộc đúng event; `refund_*`/`dispute_*` đúng family; schema
  reject `client_secret`, provider IDs, `user_id` và raw payload.
- Webhook provider test: thiếu/sai/quá hạn signature, malformed, quá size → `400` và
  không inbox/Kafka; valid → encrypted inbox + verified message rồi `200`; DB/KMS/
  Kafka lỗi → `503`; DB commit/Kafka fail rồi delivery mới không nuốt retry.
- Fixture cho Order consumer: dispatch bằng exact namespaced CloudEvents `type`,
  validate `dataschema`, `payment_transaction_id`, VND money, monotonic
  `financial_state_version`, không có `client_secret`; các alias
  `PAYMENT_FAILED/EXPIRED/CANCELED` chỉ phát sau khi intent canceled.
- Cancel/capture race: `ALREADY_SUCCEEDED` + review case, không phụ thuộc consumer
  Order event.

**E2E / failure injection:**

Initiate với lost provider response → recover → thanh toán test card → webhook →
`PAYMENT_SUCCESS`; decline retryable rồi thành công; decline non-retryable →
`FAILED`; cancel-versus-capture/processing-cancel; hết phiên → actual cancel → `EXPIRED`; refund selected-line
và admin pre-dispatch, `succeeded` ngay vs `pending`, webhook miss + poller, reversal
sau `REFUNDED`; DB/provider failure bằng fake; Kafka outage/restart và Stripe retry
webhook; secret rotation + fresh resend; dispute/funds lifecycle; fee/payout balance
reconciliation; lease crash/takeover; provider retry qua horizon; KMS key rotation;
PITR restore validation.

### 6.3. Chạy integration test từ container maven

Máy dev không có JDK thì chạy Testcontainers từ trong container maven được, với
đúng ba điều kiện (thiếu một cái sẽ báo "Could not find a valid Docker environment"
hoặc `Connection refused`):

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD:/repo" -v payment-mvn-repo:/root/.m2 \
  --add-host=host.docker.internal:host-gateway \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w /repo/java/services/payment-service \
  maven:3.9-eclipse-temurin-21 mvn -B verify '-DargLine=-Dapi.version=1.44'
```

`-Dapi.version=1.44` ghim Docker API version cho docker-java (engine mới từ chối
v1.32 mặc định); `TESTCONTAINERS_HOST_OVERRIDE` để `getHost()` không trả `localhost`
của chính container maven.

### 6.4. Stripe Sandbox E2E thủ công

Chỉ chạy sau khi `mvn verify` xanh và chỉ với `sk_test_`/`pk_test_`/`whsec_` của
sandbox riêng. Khởi động Payment + hạ tầng (§2), chạy `stripe listen` (§2.4), rồi
thực hiện lần lượt: create + card thành công, decline retryable, cancel, một refund
toàn phần, resend duplicate webhook và một dispute test. Đối chiếu cả Stripe object,
Payment read API, history, journal và outbox event ID/version. Không lưu secret hay
raw response vào artifact CI. Nếu ba biến `PAYMENT_STRIPE_SECRET_KEY`,
`PAYMENT_STRIPE_PUBLISHABLE_KEY`, `PAYMENT_STRIPE_WEBHOOK_SECRETS` không có thì bước
này phải báo `NOT RUN`, tuyệt đối không đổi sang live key hoặc giả vờ pass.

## 7. Contract lint và bundle

- Nguồn canonical: public + internal Payment OpenAPI và public + internal Payment
  AsyncAPI. Markdown trong `interfaces.md` chỉ giải thích, không phải nguồn sinh.
- Toolchain nằm ở `contracts/tools`: Node ≥ 24.11, Redocly CLI `2.53.3` và AsyncAPI
  CLI `6.1.0` được pin exact trong `package-lock.json`; không phụ thuộc global CLI.
- Chạy từ repo root:

  ```bash
  cd contracts/tools
  npm ci
  npm run verify:payment
  ```

- `verify:payment` lint bốn contract rồi bundle deterministic vào
  `contracts/generated/` (gitignored). Runtime hiện dùng handwritten narrow DTO/port;
  không tuyên bố server stub/codegen trong `pom.xml`. Contract tests kiểm operation
  IDs, structured CloudEvent, Debezium routing và exact schema/type mapping.

## 8. Database migration

- Flyway, thư mục `src/main/resources/db/migration`, versioned `V<n>__<mô_tả>.sql`,
  không dùng `R__` cho DDL; app user không có quyền DDL ở prod (migration chạy bằng
  job riêng hoặc init container với user riêng).
- `V1__payment_baseline.sql`: đủ 12 bảng theo DDL `data.md §3` (`payment_transactions`,
  `refund_transactions`, `payment_disputes`, `provider_operations`, `webhook_logs`,
  `payment_status_history`, `outbox_events`, `payment_idempotency`,
  `payment_review_cases`, `reconciliation_runs`, `financial_journals`,
  `financial_ledger_entries`) với mọi `CHECK`, unique/partial index, immutable
  ledger triggers và cặp deferred balance constraint trigger ở `data.md §4`;
  **không** `PARTITION BY`
  ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)). Thứ tự
  tạo: payment → refund → dispute → provider operation → webhook/history/outbox/
  idempotency/review/reconciliation → journal → ledger entries. Connector Gate 2
  dùng `publication.autocreate.mode=filtered` cho `outbox_events`; nếu production
  cấm Kafka Connect tạo publication, thêm migration kế tiếp để pre-provision đúng
  publication/privilege trước khi deploy connector.
- Thay đổi sau baseline theo expand/contract (`operations.md §7.2`); index trên bảng
  lớn tạo `CONCURRENTLY` (Flyway: `-- flyway:executeInTransaction=false`).
- Không có down migration cho prod; rollback bằng forward fix. Môi trường dev dùng
  `flyway:clean` + migrate.
- CI: migrate vào PostgreSQL Testcontainers rỗng, rồi migrate lần hai (idempotent),
  rồi chạy migration lint §5.

## 9. Test data và fixture

- Payment ở mọi trạng thái (`INITIATING`…`REFUNDED`); refund `PENDING`/`PROCESSING`/
  `SUCCESS`/`FAILED`/`REVERSED`; `provider_operations` `REQUESTED`/`UNKNOWN`/
  `PROVIDER_CONFIRMED`/`PROVIDER_REJECTED`; review case mở/đóng; `payment_idempotency`
  `IN_PROGRESS`/`COMPLETED`.
- Webhook payload Stripe test (đúng API version pin) cho `payment_intent.processing`,
  `payment_intent.succeeded`, `payment_intent.payment_failed` (retryable và
  non-retryable, có `last_payment_error.decline_code`), `payment_intent.canceled`
  (có/không `CANCEL` operation), `charge.refunded`, `refund.updated` với `status`
  `pending`/`succeeded`/`failed`/`canceled`/`requires_action`,
  `refund.failed` và đủ 5 dispute webhook types; mỗi fixture có helper ký
  `Stripe-Signature` với `whsec_` giả và `t` tùy chọn.
- Builder cho request Order: initiate/cancel/refund với `Idempotency-Key` ổn định và
  `X-Service-Identity: order-service`; `inventory_evidence` hai dạng.
- ID giả theo đúng prefix (`TXN_`, `RFD_`, `POP_`, `PRC_`, `DSP_`, `FJN_`),
  `pi_test_…`, `re_test_…`, `dp_test_…`, `evt_test_…`; không dùng production data.

## 10. Gỡ lỗi

- Truy vết theo `correlation_id` → `payment_transaction_id` → `payment_status_history`
  (thứ tự transition, `triggered_by`, `cause_ref`) → `provider_operations` (side
  effect nào đã xảy ra) → inbox metadata/hash/lease → disputes/journals. Giải mã raw
  chỉ qua break-glass khi còn trong 90 ngày.
- Nghi lệch với Stripe: so `provider_status_snapshot` với Dashboard/`retrieve`; chạy
  reconciliation thủ công cho một transaction (`operations.md §9.10`).
- Event không tới Order: `outbox_events` có row? connector status? `pg_replication_slots`
  lag? (`operations.md §9.3`).
- **Không** `UPDATE status` bằng SQL để "gỡ kẹt": mất audit/outbox và phá
  `financial_state_version`; dùng correction `MANUAL` qua review case
  (`WF-PAYMENT-010`).
- Log dev bật `DEBUG` cho `com.platform.paymentservice` nhưng `StripeClient` chỉ log
  request id, không log body.

## 11. Lỗi local thường gặp

- Ingress auth fail hàng loạt: `whsec_` trong env là của phiên `stripe listen` cũ.
- Đồng hồ máy lệch > 300s → mọi webhook bị reject theo cửa sổ.
- Debezium không route: thiếu `aggregate_type=Payment`, `partition_key` null, hoặc
  connector chưa đăng ký sau `docker compose down -v`.
- Nếu accelerator Redis được thêm sau Gate 3, lock TTL không được thay DB
  lease/fencing; stale completion và provider duplicate vẫn bị PostgreSQL chặn.
- Nhân `amount_minor` với 100 trước khi gửi Stripe: VND là zero-decimal,
  `amount_minor` truyền nguyên (100000 = 100.000 ₫); nhân 100 sẽ sai 100 lần.
- Local AES key khác nhau giữa hai lần chạy → `client-data` trả `usable=false` dù attempt
  còn `PENDING`.
- Coi `payment_intent.payment_failed` là terminal trong test → sai với ADR-014.
- Gọi Stripe bên trong `@Transactional` → ArchUnit A3 fail; nếu lọt, pool cạn dưới tải.
- Testcontainers "Could not find a valid Docker environment" → thiếu `-Dapi.version=1.44` (§6.3).

## 12. Definition of Done

Một thay đổi chỉ hoàn tất khi:

1. `mvn verify` pass (unit, PostgreSQL integration/Flyway và ArchUnit); chạy
   riêng contract/docs validators và runtime payload script. Maven hiện không có
   contract codegen hoặc secret-scan plugin. Secret scan và các gate môi trường
   là yêu cầu kiểm chứng riêng, không suy ra từ Maven pass.
2. Mọi transition mới/sửa có test ở tầng unit và, nếu chạm DB/Stripe, tầng integration;
   FSM coverage test vẫn pass.
3. Transition + history + balanced journal + outbox + inbox terminal/review atomic;
   không side effect Stripe nào thiếu ProviderOperation + lease/fencing.
4. Không secret/PII trong log, test fixture, outbox, idempotency response; raw
   webhook chỉ encrypted và retention đúng ADR-020.
5. Tài liệu đồng bộ: `domain.md`/`data.md`/`interfaces.md`/`workflows.md`/`operations.md`
   theo phạm vi thay đổi; contract file sửa trước, doc theo sau; quyết định khó đảo
   ngược có ADR mới (không sửa ADR đã accepted).
6. Với thay đổi liên service: `docs/order`, `docs/mesh/runtime-contract.md`,
   `docs/gateway/runtime-contract.md`, `contracts/catalog/interactions.yaml` được rà.
7. Frontmatter `evidence`/`verified_against_commit` chỉ cập nhật khi có code thật;
   trạng thái chỉ rời `proposed` sau gate ở `README.md §1`.

### Kiểm chứng audit runtime payload

Sau `mvn verify`, chạy `node java/services/payment-service/scripts/validate-payment-runtime-events.cjs`
từ gốc repo (cần `npm ci` tại `contracts/tools`). Script dùng canonical JSON Schema,
kiểm 15 example và ba event thực sự phát bởi ManualCorrectionIT, cùng conditional
request schema FAILED/CANCELED. V3 giữ 12 bảng nhưng cho phép net refund/fee totals âm.
