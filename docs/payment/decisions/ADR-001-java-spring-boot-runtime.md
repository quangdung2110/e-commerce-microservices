---
doc_id: payment-adr-001
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/architecture.md
  - docs/payment/development.md
---

# ADR-001 — Dùng Java (Spring Boot) cho Payment Service

## Trạng thái

`Accepted` trong thiết kế; chưa xác minh implementation. Ngày quyết định gốc không
được ghi lại. `contracts/catalog/services.yaml` ghi runtime `java-planned`.

## Bối cảnh

Payment là service nhạy cảm nhất (xử lý tiền thật): PostgreSQL-first với ACID tối
thượng; dual FSM (Payment Transaction + Refund Transaction) với nhiều transition
và edge case (webhook race, timeout, retry, replay/out-of-order từ Stripe retry
3–5 lần); verify `Stripe-Signature` HMAC-SHA256; reconciliation hourly với Stripe
API; concurrency bằng optimistic locking + Redis distributed lock. Cần chọn ngôn
ngữ/framework giữa Java (Spring Boot) và Golang (Gin).

## Quyết định

Chọn **Java (Spring Boot)** với tech stack:

| Component | Công nghệ |
|---|---|
| Framework | Spring Boot 3.x + Spring Web |
| FSM | Spring State Machine (Payment FSM + Refund FSM), declarative config, guard conditions, audit trail qua Spring Event Listener |
| Database | PostgreSQL + Spring Data JPA; `@Transactional` gom transaction + outbox + status history; `@Version` optimistic locking |
| Kafka | Spring Kafka (webhook raw dump, outbox relay, event production) |
| Redis | Lettuce (idempotency); Redisson (distributed lock) |
| Security | Java Crypto (`javax.crypto`) HMAC-SHA256/512; Stripe SDK `Webhook.constructEvent` |
| HTTP Client | Spring WebClient / OkHttp (gọi Stripe API) |
| Scheduling | Spring `@Scheduled` + Spring Retry (Reconciliation, Expiration Scanner, Refund Poller) |
| Build | Maven + JDK 21 |

## Phương án đã cân nhắc

- **Golang (Gin):** thiếu FSM framework nên phải tự implement hai FSM phức tạp +
  toàn bộ guard; Stripe có Go SDK nhưng Java SDK mature hơn cho finance domain
  (long-term support, community, examples); manual transaction management cho
  money-handling là rủi ro cao. Không chọn.
- **Java (Spring Boot):** Spring State Machine cho FSM declarative; `@Transactional`
  + `@Version` giảm boilerplate và rủi ro; Stripe Java SDK cung cấp sẵn webhook
  verify đúng chuẩn (kèm timestamp window 300s); `@Scheduled` + Spring Retry cho
  cronjob. Được chọn.

## Hệ quả

Tích cực: FSM declarative, transactional integrity dễ đảm bảo, SDK mature, hệ sinh
thái scheduling/retry sẵn có. Tiêu cực: runtime nặng hơn Go, startup/memory cao
hơn, cần JDK 21 + Maven trong build pipeline (repository hiện build Java qua
Docker Maven).

## Ảnh hưởng migration

Chưa có code; quyết định định hình cấu trúc package (`com.payment.stripe`,
`src/refund/`), build (Maven), và dependency Spring State Machine/Kafka/Redisson.

## Xác minh

Cần `pom.xml` với các dependency trên, Spring State Machine config cho hai FSM,
`@Transactional` service gom transaction + outbox + history, test FSM và guard,
scheduler config cho ba cronjob.
