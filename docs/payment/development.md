---
doc_id: payment-development
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Hướng dẫn phát triển

> Checklist để bắt đầu implementation `java/services/payment-service` theo đúng
> quy ước của các Java service đã có trong repo (`promotion-service`,
> `review-service`, …) và theo thiết kế trong bộ tài liệu này. Chưa có code, nên
> mọi command là **command mục tiêu** (sẽ thành command thật khi module tồn tại),
> không phải command copy-paste đã được xác minh. Dữ liệu Stripe luôn là **test
> mode**; không có bước nào dùng live key.

## 1. Điều kiện tiên quyết

| Thành phần | Yêu cầu | Ghi chú |
|---|---|---|
| Java / build | JDK 21, Maven 3.9, Spring Boot 3.3.x (parent như `promotion-service/pom.xml`) | Máy dev không cần JDK: build bằng container `maven:3.9-eclipse-temurin-21` (§2) |
| Container runtime | Docker Engine + Compose v2 | `docker-compose.yml` ở gốc repo cung cấp PostgreSQL, Redis, Kafka, Kafka Connect (Debezium 3.0) |
| Stripe | Tài khoản Stripe test mode, một **Sandbox riêng** cho mỗi dev; Stripe CLI ≥ 1.19 | `stripe login` với sandbox; không dùng chung `whsec_` giữa các máy |
| Secret local | `sk_test_…`, `pk_test_…`, `whsec_…` từ Stripe CLI, một dev KMS key cố định | Đặt trong `.env.local` (gitignored), không bao giờ trong `application*.yml` |
| Công cụ test | Testcontainers, WireMock, `stripe/stripe-mock` (Docker), Toxiproxy | Chi tiết §6 |
| Contract tooling | `openapi-generator`/`asyncapi` CLI theo quy ước repo | §7 |

Dependency Maven bắt buộc (khớp ADR-001): `spring-boot-starter-web`, `-validation`,
`-data-jpa`, `-data-redis` (+ `redisson-spring-boot-starter`), `-actuator`,
`spring-kafka`, `flyway-core` + `flyway-database-postgresql`, `postgresql`,
`spring-statemachine-core`, `spring-retry`, `com.stripe:stripe-java`,
`micrometer-registry-prometheus`, `uuid-creator`/ULID, `archunit-junit5` (test),
`spring-boot-starter-test`, `spring-kafka-test`, `testcontainers` (postgresql,
kafka, toxiproxy), `wiremock`.

## 2. Chạy local

### 2.1. Hạ tầng

```bash
docker compose up -d postgres redis kafka kafka-connect
```

`init-db.sh` phải có thêm `CREATE DATABASE payment_service;` (chưa có — thêm cùng
commit tạo module). Port host đề xuất cho Payment: `8089:8080` (các port 8081–8088
đã dùng).

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

hoặc `docker compose up payment-service` sau khi thêm service vào compose (build
từ `java/services/payment-service/Dockerfile`, label Traefik health path
`/actuator/health`, `SPRING_PROFILES_ACTIVE=prod`).

### 2.4. Webhook local

```bash
stripe listen --forward-to localhost:8089/v1/payments/webhook/stripe \
  --events payment_intent.processing,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,charge.refund.updated,refund.failed,charge.dispute.created
```

Lệnh in ra `whsec_…` — đặt vào `PAYMENT_STRIPE_WEBHOOK_SECRETS` rồi khởi động lại
(hoặc reload config). Không đăng ký endpoint local trên Stripe Dashboard.

### 2.5. Debezium connector

Artifact `src/main/resources/kafka/debezium-connector-payment-outbox.json` (theo mẫu
của `promotion-service`), đăng ký:

```bash
curl -sS -X PUT http://localhost:8084/connectors/payment-outbox-connector/config \
  -H 'Content-Type: application/json' -d @- < <(jq .config java/services/payment-service/src/main/resources/kafka/debezium-connector-payment-outbox.json)
```

Connector: `table.include.list=public.outbox_events`, outbox `EventRouter` với
`route.by.field=aggregate_type` → `t.payment.events.v2`, key từ `partition_key`,
`expand.json.payload=true`; `payload` đã là full CloudEvents structured JSON và phải
đến Kafka value nguyên vẹn. Header từ cột `headers` chỉ mirror ID/type/correlation/
trace cho observability, không được dùng để dựng lại hoặc hiểu event.

### 2.6. Kiểm tra và dọn dẹp

- `GET /actuator/health/readiness` → `UP` khi PostgreSQL + KMS key + Stripe key nạp
  được; `GET /internal/health/deps` liệt kê từng dependency (`operations.md §1.2`).
- Smoke: `POST /v1/payments/initiate` với `Idempotency-Key` → `PENDING` + `client_secret`;
  `stripe trigger payment_intent.succeeded` → `webhook_logs.status = PROCESSED`.
- Dọn: `docker compose down -v` xóa volume; `stripe listen` dừng bằng Ctrl-C.

## 3. Dependency local

| Dependency | Compose service / port | Khởi tạo | Mock được? |
|---|---|---|---|
| PostgreSQL 16 | `postgres` / 15432 | DB `payment_service`; Flyway tự chạy migration khi start | Không (Testcontainers ở test) |
| Redis 7 | `redis` / 6379 | Không cần seed | Có — tắt bằng `payment.redis.enabled=false`, mọi guard fallback DB |
| Kafka | `kafka` / 9092 (nội bộ), 29092 (host) | `t.payment.webhooks.raw` (6 partition, 7d, 256 KiB) và `t.payment.events.v2` (6 partition, ≥15d, 64 KiB) tự tạo bởi `KafkaAdmin` ở profile `dev` | Không |
| Kafka Connect (Debezium) | `kafka-connect` / 8084 | Đăng ký connector §2.5 | Có — bỏ qua khi chỉ test API; outbox vẫn được ghi |
| Stripe | Internet, test mode | Sandbox riêng + Stripe CLI | Có — `stripe-mock`/WireMock (§6) |
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
| `PAYMENT_STRIPE_API_VERSION` | `payment.stripe.api-version` | Có | version pin, giống mọi môi trường |
| `PAYMENT_CRYPTO_LOCAL_KEY` | `payment.crypto.local-key` (chỉ profile `dev`/`test`) | Có | base64 32 byte |
| `PAYMENT_SESSION_TTL` | `payment.session.ttl` | Không | `PT15M`; test nhanh: `PT2M` |
| `PAYMENT_STRIPE_DECLINE_MAX_PER_ATTEMPT` | `payment.stripe.decline.max-per-attempt` | Không | `5` |
| `PAYMENT_SCHEDULER_*` | `payment.scheduler.*` | Không | Interval rút ngắn cho dev (`expiration.interval=PT10S`) |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | Có | `jdbc:postgresql://localhost:15432/payment_service` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `spring.kafka.bootstrap-servers` | Có | `localhost:29092` |
| `SPRING_DATA_REDIS_HOST` | `spring.data.redis.host` | Không | `localhost` |

Quy tắc: `application.yml` chỉ chứa default không nhạy cảm và placeholder `${…}`;
`application-dev.yml` cho interval ngắn/log debug; `application-prod.yml` không có
giá trị secret nào; `.env.local` gitignored. CI fail nếu grep thấy `sk_live_`,
`pk_live_`, `whsec_` literal trong repo. Profile `test` không bao giờ đọc env Stripe
thật.

## 5. Build, lint và format

| Bước | Command mục tiêu | Pass khi |
|---|---|---|
| Compile + unit | `mvn -B verify -DskipITs` | 0 failure; ArchUnit rules A1–A12 (`architecture.md §7`) pass |
| Integration | `mvn -B verify` (Failsafe `*IT`) | Testcontainers PostgreSQL/Kafka/Redis up; xem §6.3 về Docker API version |
| Format/lint | Spotless (google-java-format) + Checkstyle theo parent repo | Không diff sau `spotless:apply` |
| Migration lint | Script CI: không `PARTITION BY`; mọi bảng có `created_at`; unique/partial index theo `data.md §4` | Grep pass |
| Contract lint | `openapi lint`, `asyncapi validate`; codegen không drift (§7) | 0 diff |
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
| Integration | `stripe-mock` (container) cho contract SDK ↔ API shape; WireMock cho hành vi `stripe-mock` không mô phỏng được | Testcontainers: PostgreSQL, Redis, Kafka + Connect | Mọi PR, offline | `mvn verify` |
| E2E | Stripe **test mode** (sandbox riêng) + Stripe CLI `listen`/`trigger` + test card | Compose local hoặc staging | Thủ công khi dev; nightly trên `main` | `mvn verify -Pe2e` với secret CI |
| Failure injection | WireMock fault (timeout, 5xx, ambiguous) cho Stripe; Toxiproxy cho Redis/Kafka/DB | Testcontainers | Nightly / trước release | `mvn verify -Pchaos` |

**Ranh giới giữa các hình thái Stripe.**

- `stripe-mock` **stateless**: không lưu object giữa các call, không bắn webhook,
  không kiểm tra `Idempotency-Key`, trả dữ liệu sinh từ OpenAPI spec. Chỉ dùng để
  khẳng định code gọi SDK đúng shape. Mọi assertion về *hành vi* (replay cùng key
  trả cùng `pi_xxx`, cancel-versus-capture, `Refund.retrieve` đổi trạng thái) phải
  dùng WireMock với stub có state, hoặc test mode thật.
- Ambiguous transport result (timeout sau khi Stripe đã tạo PaymentIntent) chỉ mô
  phỏng được bằng WireMock fault; test mode thật không tái hiện được có chủ đích.
  Đây là kịch bản bắt buộc cho recovery ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
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
| `4000 0000 0000 0002` × 6 lần | Quá `max_declines_per_attempt` | như trên | `T3` `FAILED`, `error_code=DECLINE_LIMIT_EXCEEDED` |
| `4000 0000 0000 0259` | Thành công rồi bị dispute | `charge.dispute.created` | `SUCCESS` + review case `DISPUTE_OPENED` |
| `4000 0000 0000 5126` | Thanh toán thành công, refund thất bại async | `charge.refund.updated` `status=failed` | `R3`/`T8` (nếu còn `PROCESSING`) hoặc `R5`/`T9` `REVERSED` (nếu đã `SUCCESS`) + review case |
| Không thanh toán trong `payment_session_ttl` | — | Scanner `PaymentIntent.cancel` → `payment_intent.canceled` | `T4` `EXPIRED` (chỉ sau khi intent canceled) |

**Kịch bản webhook không tạo được bằng thanh toán thường.**

- Duplicate: `stripe trigger <event>` hai lần, hoặc "Resend" từ Dashboard → dedup
  theo `stripe_event_id`, `ON CONFLICT DO NOTHING`.
- Out-of-order (`payment_failed` đến sau `succeeded`): Stripe CLI không kiểm soát thứ
  tự; replay fixture đã ký thẳng vào `POST /v1/payments/webhook/stripe` theo thứ tự
  chủ đích (tầng integration).
- Webhook miss: tắt `stripe listen`, thanh toán bằng test card, kiểm tra
  Reconciliation (payment) hoặc Refund Poller (refund) chốt trạng thái từ
  `PaymentIntent.retrieve`/`Refund.retrieve`; bật lại `listen` để xác nhận webhook
  đến muộn bị bỏ qua idempotent.
- Đổi `whsec_`: restart `stripe listen` sinh secret mới → xác nhận `FAILED_SIGNATURE`
  rồi auto-retry pass sau khi thêm secret mới vào danh sách.
- Retry 3–5 lần của Stripe: endpoint trả `503` có chủ đích (feature flag test) →
  quan sát Stripe retry và xác nhận không xử lý trùng.

**Thời gian.** Stripe Test Clocks chỉ áp dụng cho Billing/subscription, không đẩy
nhanh timeout của PaymentIntent. Mọi scheduler nhận `java.time.Clock` (`ClockPort`)
để unit/integration test tua thời gian; tầng E2E dùng profile với interval rút
ngắn (`payment.session.ttl=PT2M`, `expiration.interval=PT10S`) thay vì chờ thật.

**Cổng CI.**

- Mỗi PR: unit + integration (offline, `stripe-mock`/WireMock/Testcontainers); không
  cần secret Stripe.
- Nightly trên `main` / trước release: E2E test mode với key sandbox `ci` từ CI secret
  store; failure injection đầy đủ.
- Bất kỳ transition nào trong bảng FSM (`domain.md §6`) chưa có test ở tầng unit →
  CI fail (test liệt kê transition đã cover so với enum).

### 6.2. Case bắt buộc theo tầng

**Unit (domain + application với port fake):**

- Payment FSM `T0`–`T9` (kể cả `T3a` audit-only), Refund FSM `R1`–`R5`, `CORR`,
  invalid transition, state precedence theo `event_created_at`.
- Policy decline (`RULE-PAY-014`): retryable → `PENDING`; non-retryable/quá ngưỡng →
  `CANCEL` operation → `FAILED`; cancel trả `succeeded` → `T2`.
- `financial_state_version` +1 đúng một lần cho mỗi outbox event, không tăng ở `T3a`.
- Guard: signature (đúng/sai/ngoài 300s/secret thứ hai), amount/reference mismatch,
  idempotency fingerprint (`IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`), đặc biệt
  cancel/refund dùng cùng key + body trên `transactionId` khác phải bị `422`.
- Refund: one-lifetime per `order_id`, `amount ≤ amount_minor`, evidence bắt buộc,
  mixed inspection PASS/FAIL không block.
- Mapping Stripe event → transition theo `interfaces.md §4`; `charge.refund.updated`
  `status=failed` → `R3`/`T8` hay `R5`/`T9` tùy trạng thái refund; `payment_intent.canceled`
  → terminal theo `CANCEL` operation đã ghi (hoặc `PROVIDER_CANCELED` khi không có).
- `client-data`: `usable` theo `status`/`client_expires_at`; ciphertext bị xóa khi rời
  `PENDING`; key rotation giải mã được key cũ.
- ArchUnit A1–A12.

**Integration (Testcontainers):**

- PostgreSQL: transition + outbox + history atomic (rollback giữa chừng không để lại
  row nào); optimistic conflict giữa hai webhook; `ON CONFLICT (stripe_event_id) DO
  NOTHING`; hai `payment_failed` khác `evt_` trên cùng intent đều được ghi; partial
  unique một `CANCEL`/payment khi scanner và Order cancel đồng thời; unique
  `refund_transactions.order_id` khi hai refund khác key; partial unique một review
  case mở/payment.
- Redis: SETNX/lock hit/miss/outage; fallback `payment_idempotency` (fingerprint +
  `IN_PROGRESS`) và `refund_transactions.command_idempotency_key`; worker bỏ lượt
  khi không lấy được lock.
- Debezium: outbox → `t.payment.events.v2` at-least-once, key đúng
  `data.order_id`, Kafka value là full structured CloudEvent; header mirror không
  phải authority; connector restart không mất và duplicate giữ cùng `(source,id)`.
- Stripe (WireMock stateful): `PaymentIntent.create`/`cancel` với cùng key trả cùng
  object; ambiguous transport (timeout sau khi tạo) → operation `UNKNOWN` → recovery
  bind; `idempotency_error` → `PROVIDER_REJECTED` + review case; `Refund.create` với
  cùng key; `Refund.retrieve` mọi trạng thái.
- Scheduler: scanner cancel intent rồi expire; không đụng `INITIATING`/`PROCESSING`;
  cancel trả `succeeded` → `SUCCESS`; poller chốt khi webhook miss; recovery replay
  cùng key và bind `pi_`/`re_`; reconciliation `CORR` idempotent và chỉ mở review case
  khi freeze; trimmer không xóa outbox trên CDC watermark.
- Review case: `requires_manual_review` đồng bộ với case; đóng thiếu
  `resolution_type` bị reject; correction `MANUAL` ghi `actor`/`cause_ref`.

**Contract/provider/consumer:**

- Provider test: implementation ↔ `openapi.yaml` (7 operation, problem+json code ở
  `interfaces.md §2.3`) và ↔ financial/internal AsyncAPI. Cả 10 exact namespaced
  type phải khớp `dataschema`; `expires_at` chỉ ở `PAYMENT_INITIATED`; `error_code`/
  `reason_code` bắt buộc đúng event; `refund_*` bắt buộc với `REFUND_*`; schema
  reject `client_secret`, provider IDs, `user_id` và raw payload.
- Webhook provider test: thiếu body/`Stripe-Signature` → `400`; envelope đủ được
  Kafka ack → `200` kể cả body không deserialize được hoặc signature sai (worker mới
  verify/parse; malformed body dùng key hash); base64 decode phải trả đúng byte đã
  ký; candidate ID không được dùng làm identity trước verify; Kafka produce lỗi → `503`.
- Fixture cho Order consumer: dispatch bằng exact namespaced CloudEvents `type`,
  validate `dataschema`, `payment_transaction_id`, VND money, monotonic
  `financial_state_version`, không có `client_secret`; các alias
  `PAYMENT_FAILED/EXPIRED/CANCELED` chỉ phát sau khi intent canceled.
- Cancel/capture race: `ALREADY_SUCCEEDED` + review case, không phụ thuộc consumer
  Order event.

**E2E / failure injection:**

Initiate với lost provider response → recover → thanh toán test card → webhook →
`PAYMENT_SUCCESS`; decline retryable rồi thành công; decline non-retryable →
`FAILED`; cancel-versus-capture; hết phiên → cancel → `EXPIRED`; refund selected-line
và admin pre-dispatch, `succeeded` ngay vs `pending`, webhook miss + poller, reversal
sau `REFUNDED`; Redis/Kafka/DB/Stripe outage (Toxiproxy/WireMock fault); Stripe retry
webhook; đổi webhook secret + auto-retry; KMS key rotation.

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

## 7. Contract code generation

- Nguồn: `contracts/http/payment/v1/openapi.yaml` (server stub interface + DTO
  snake_case) và `contracts/events/payment/v2/asyncapi.yaml` (event `data` record +
  JSON Schema validator cho outbox payload).
- Generator pin version trong `pom.xml`; output vào `target/generated-sources`,
  **không** commit; CI regenerate và fail nếu diff với interface đang dùng.
- Controller implement interface sinh ra; `ProblemDetail` map theo bảng
  `interfaces.md §2.3`.
- Outbox writer validate `payload` bằng JSON Schema từ AsyncAPI **trước khi insert**
  (fail-fast thay vì phát event sai schema); test dùng cùng validator.
- Markdown ví dụ trong `interfaces.md` không phải nguồn sinh code.

## 8. Database migration

- Flyway, thư mục `src/main/resources/db/migration`, versioned `V<n>__<mô_tả>.sql`,
  không dùng `R__` cho DDL; app user không có quyền DDL ở prod (migration chạy bằng
  job riêng hoặc init container với user riêng).
- `V1__payment_baseline.sql`: đủ 9 bảng theo DDL `data.md §3` (`payment_transactions`,
  `refund_transactions`, `provider_operations`, `webhook_logs`,
  `payment_status_history`, `outbox_events`, `payment_idempotency`,
  `payment_review_cases`, `reconciliation_runs`) với mọi `CHECK`, unique và partial
  index ở `data.md §4`; **không** `PARTITION BY`
  ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)). Thứ tự
  tạo: `payment_transactions` → `refund_transactions` → `provider_operations` (FK cả
  hai) → còn lại. `V2__debezium_publication.sql`: `PUBLICATION` cho `outbox_events`
  và `REPLICA IDENTITY`.
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
  (có/không `CANCEL` operation), `charge.refunded`, `charge.refund.updated`
  `status=failed`, `refund.failed`, `charge.dispute.created`; mỗi fixture có helper ký
  `Stripe-Signature` với `whsec_` giả và `t` tùy chọn.
- Builder cho request Order: initiate/cancel/refund với `Idempotency-Key` ổn định và
  `X-Service-Identity: order-service`; `inventory_evidence` hai dạng.
- ID giả theo đúng prefix (`TXN_`, `RFD_`, `POP_`, `PRC_`), `pi_test_…`, `re_test_…`,
  `evt_test_…`; `user_id` UUID; không dùng dữ liệu production.

## 10. Gỡ lỗi

- Truy vết theo `correlation_id` → `payment_transaction_id` → `payment_status_history`
  (thứ tự transition, `triggered_by`, `cause_ref`) → `provider_operations` (side
  effect nào đã xảy ra) → `webhook_logs` (`raw_body` + `processing_error`).
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

- `FAILED_SIGNATURE` hàng loạt: `whsec_` trong env là của phiên `stripe listen` cũ.
- Đồng hồ máy lệch > 300s → mọi webhook bị reject theo cửa sổ.
- Debezium không route: thiếu `aggregate_type=Payment`, `partition_key` null, hoặc
  connector chưa đăng ký sau `docker compose down -v`.
- Redis lock TTL < Stripe read timeout → hai worker cùng gọi Stripe (idempotency key
  vẫn bảo vệ, nhưng log conflict tăng).
- Nhân `amount_minor` với 100 trước khi gửi Stripe: VND là zero-decimal,
  `amount_minor` truyền nguyên (100000 = 100.000 ₫); nhân 100 sẽ sai 100 lần.
- Dev KMS key khác nhau giữa hai lần chạy → `client-data` trả `usable=false` dù attempt
  còn `PENDING`.
- Coi `payment_intent.payment_failed` là terminal trong test → sai với ADR-014.
- Gọi Stripe bên trong `@Transactional` → ArchUnit A3 fail; nếu lọt, pool cạn dưới tải.
- Testcontainers "Could not find a valid Docker environment" → thiếu `-Dapi.version=1.44` (§6.3).

## 12. Definition of Done

Một thay đổi chỉ hoàn tất khi:

1. `mvn verify` pass (unit + integration + ArchUnit + migration lint + contract
   codegen không drift + secret scan).
2. Mọi transition mới/sửa có test ở tầng unit và, nếu chạm DB/Stripe, tầng integration;
   FSM coverage test vẫn pass.
3. Transition + history + outbox (+ review case) atomic không bị phá; không side effect
   Stripe nào thiếu `ProviderOperation`.
4. Không secret/PII trong log, test fixture, outbox, idempotency response.
5. Tài liệu đồng bộ: `domain.md`/`data.md`/`interfaces.md`/`workflows.md`/`operations.md`
   theo phạm vi thay đổi; contract file sửa trước, doc theo sau; quyết định khó đảo
   ngược có ADR mới (không sửa ADR đã accepted).
6. Với thay đổi liên service: `docs/order`, `docs/mesh/runtime-contract.md`,
   `docs/gateway/runtime-contract.md`, `contracts/catalog/interactions.yaml` được rà.
7. Frontmatter `evidence`/`verified_against_commit` chỉ cập nhật khi có code thật;
   trạng thái chỉ rời `proposed` sau gate ở `README.md §1`.
