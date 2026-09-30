# Payment Service implementation gates

Checklist này quyết định khi nào thiết kế được phép đi tiếp. Một gate chỉ `PASS`
khi mọi điều kiện có bằng chứng chạy được; không đánh dấu thủ công thay cho test.

## Gate 1 — thiết kế nhất quán: PASS

- Một refund single-shot trọn đời/order, một selected scope; mọi selected line hoàn
  toàn bộ ordered quantity. Không partial quantity hoặc incremental/multiple refund.
- Payment sở hữu payment, refund, dispute, fee, payout, operational double-entry
  subledger và reconciliation.
- ADR, FSM, schema logic, OpenAPI, AsyncAPI Payment/Order, examples, workflow,
  operations và development guide đồng bộ.
- Webhook được xác thực trước persistence/Kafka và vào durable inbox. Provider
  command có durable operation, lease/fencing, safety horizon và không blind retry.
  Provider event có per-aggregate ordering checkpoint.
- Bằng chứng: `validate_docs.ps1` pass; Payment JSON/contracts parse; scoped diff
  check không có whitespace error.

## Gate 2 — executable contract và persistence foundation: PASS

- 15 financial facts có schema + example + AsyncAPI binding; Order nhận đúng 13
  facts liên quan, không nhận hai progress facts `PAYMENT_INITIATED` và
  `REFUND_INITIATED`.
- Public API, internal Finance Ops API và verified-webhook envelope có validator.
- Migration PostgreSQL baseline tạo đúng 12 bảng canonical, unique/partial indexes,
  one-refund guard, provider recovery, inbox leases, provider checkpoints,
  append-only history/evidence, reconciliation singleton và idempotency actions.
- Subledger có immutable journal/lines, cặp deferred balanced-journal constraint
  trigger và stored procedure duy nhất để post journal.
- Debezium route full structured CloudEvent theo `order_id` tới
  `t.payment.events.v2`, không tombstone và fail closed.
- Bằng chứng bắt buộc:

  ```powershell
  pwsh -NoProfile -File java/services/payment-service/scripts/validate-payment-gate2.ps1
  ```

Gate 2 không khẳng định runtime hoàn tất. Migration smoke test trên PostgreSQL,
constraint integration test, semantic OpenAPI/AsyncAPI lint và Spring Boot runtime
thuộc Gate 3.

## Gate 3 — runtime skeleton và integration safety: PASS

- [x] Spring Boot khởi động với Flyway/JPA validation trên PostgreSQL 16 thật.
- [x] Domain thuần, application ports, adapter package boundaries và ArchUnit rules.
- [x] Payment/Refund/Dispute FSM, guard/invariant và exhaustive invalid-transition tests.
- [x] Atomic financial commit cho aggregate/history/outbox/review/inbox.
- [x] HTTP idempotency executor: canonical fingerprint, DB lease/fencing, replay và
  crash takeover; Redis không nằm trên correctness path.
- [x] Integration tests chứng minh constraints/triggers, transaction boundaries và
  concurrent idempotency/lease takeover.
- [x] ProviderOperation framework: first-class operation lease/fencing, TX1 claim,
  provider call ngoài transaction, TX2 CAS bind bốn outcome, safety horizon 23 giờ
  và search-before-recreate.
- [x] Initiate payment: TX1 atomic tạo command slot + `INITIATING` + CREATE
  operation; Stripe call ngoài transaction; TX2 fencing atomic bind intent, secret
  ciphertext, `T0.1`, idempotency, history và full `PAYMENT_INITIATED` CloudEvent đã
  implement. CREATE bị provider reject đã atomic mở `PROVIDER_CREATE_REJECTED` và
  lưu stable `503`; PostgreSQL/Testcontainers integration suite pass.
- [x] Initiate recovery: ambiguous response giữ `INITIATING`/`UNKNOWN`, replay dùng
  lại đúng provider key trước 23 giờ và search-before-recreate sau horizon.
- [x] Client-data API: chỉ giải mã khi `PENDING` và trước `client_expires_at`, luôn
  `private, no-store`; plaintext bị cấm khỏi idempotency/history/outbox/provider JSON;
  có test key rotation và missing-old-key degradation.
- [x] Cancel/expiration: stable `CANCEL` operation, Stripe actual-fact mapping,
  TX1/TX2 fencing, T2/T4/T5, `CAPTURE_AFTER_CANCEL`, amount/reference guard,
  Order-versus-scanner reuse, `SKIP LOCKED` scanner và decline T3a/T3
  (`DECLINED_NON_RETRYABLE`/`DECLINE_LIMIT`) đã implement và integration suite pass,
  gồm race Order tiếp quản operation do expiration scanner tạo.
- [x] Verified webhook ingress: hard limit exact raw body 256 KiB; Stripe HMAC +
  300-second recency với tối đa hai secret; malformed/untrusted delivery không vào
  DB/Kafka; encrypted durable inbox upsert; duplicate terminal no-op; structured
  internal CloudEvent key `STRIPE:<stripe_event_id>` và HTTP `200` chỉ sau Kafka ack
  đã implement. Unit/controller/ArchUnit, Kafka-ack adapter tests và PostgreSQL inbox
  integration suite pass.
- [x] Webhook processor/inbox: Kafka manual ack boundary, exact-body SHA-256 check,
  encrypted inbox read, atomic `RECEIVED/RETRY_WAIT/expired PROCESSING` claim,
  lease takeover, fencing, exponential DB retry, `DEAD`, retry scanner, checkpoint,
  amount/currency guard và các nhánh PaymentIntent `processing`/`succeeded`/
  `payment_failed`/`canceled`, refund `R2/R3/R5`, dispute `D1`…`D5`, financial journal
  và manual replay maker-checker đã implement. Tie cùng timestamp gọi
  `PaymentIntent.retrieve`/`Refund.retrieve`/`Dispute.retrieve`; transport failure
  quay durable retry, 4xx/retrieve hoặc dispute-funds ambiguity vào review thay vì
  lexical ordering hay suy luận money movement. Unit và PostgreSQL integration pass.
- [x] Refund command: explicit Order command, hai schema Inventory evidence, canonical
  HTTP idempotency, one-refund/order guard, TX1 `PENDING` + `T6` + durable `REFUND`
  operation + `REFUND_INITIATED`, Stripe call ngoài transaction, TX2 fenced `R1`
  và `R2/T7` khi provider trả `succeeded`, provider reject `R4/T8`, structured outbox
  và balanced refund journal đã implement. Stripe adapter có same-key create và
  search-before-recreate theo metadata đầy đủ. Refund Poller 10 phút quét
  `PROCESSING` quá 1 phút, `Refund.retrieve`, ghi provider snapshot cho `pending`,
  và atomic `R2/T7` hoặc `R3/T8` + history/outbox/journal/review; optimistic version
  giải quyết race với webhook. Unit và PostgreSQL integration pass.
- [x] Recovery workers đầy đủ: Provider-operation Recovery chạy trước các worker
  phụ thuộc; Expiration Scanner, Refund Poller, Webhook Retry, object-state và
  balance-ledger Reconciliation, Retention Trimmer đều có scheduler/runtime.
  Reconciliation bao phủ Payment/Refund/Dispute, balance movement fee/payout/
  dispute withdrawal-reinstatement, freeze control bền vững và `CORR` qua FSM.
  Trimmer có PostgreSQL advisory singleton, legal hold/export manifest và outbox
  cleanup fail-closed dưới CDC watermark.
- [x] Admin/review tooling: đủ 9 operation internal OpenAPI; claim/resolve/replay/
  reconcile/freeze/correction idempotent, maker-checker, actor/cause audit; manual
  correction đi qua FSM; amount/dispute correction yêu cầu Accounting approval;
  journal/dispute query read-only và không có direct status mutation API.
- [x] Outbox CDC giữ full structured CloudEvent, partition key `order_id`, stable
  event id, connector config không tombstone và cleanup chỉ dưới confirmed slot LSN.
- [x] OpenAPI/AsyncAPI lint + bundle bằng toolchain pin trong
  `contracts/tools/package-lock.json`; public/internal adoption status là
  `implemented`.
- [x] Dockerfile non-root, Compose profile `payment`, init DB và liveness healthcheck
  đã có; Payment runtime không phụ thuộc Redis, PostgreSQL là correctness authority.
- [x] Stripe adapter + fake gateway tests bao phủ create/cancel/refund/retrieve,
  lost response, same-key replay, safety horizon/search-before-recreate, webhook
  ordering và invalid provider facts.

### Bằng chứng Gate 3

- `mvn -q verify`: PASS, gồm unit/controller/ArchUnit và PostgreSQL 16
  Testcontainers integration suite; context test tắt scheduler bằng property để
  không có background task chạy vào container đã đóng.
- `pwsh -NoProfile -File validate_docs.ps1`: PASS.
- `pwsh -NoProfile -File java/services/payment-service/scripts/validate-payment-gate2.ps1`:
  PASS regression Gate 1/2.
- `npm run verify:payment` trong `contracts/tools`: PASS cho hai OpenAPI và hai
  AsyncAPI, đồng thời tạo bốn bundle.
- `docker compose --profile payment config --quiet` và
  `docker build --check java/services/payment-service`: PASS.
- Stripe Sandbox E2E: `NOT RUN` trên máy kiểm chứng vì không có ba biến test secret
  `PAYMENT_STRIPE_SECRET_KEY`, `PAYMENT_STRIPE_PUBLISHABLE_KEY`,
  `PAYMENT_STRIPE_WEBHOOK_SECRETS`. Đây là release-environment validation riêng,
  không được thay bằng fake và không làm sai bằng chứng offline của Gate 3.
