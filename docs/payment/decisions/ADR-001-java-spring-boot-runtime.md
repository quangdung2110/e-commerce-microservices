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

`Accepted`; runtime skeleton đã được xác minh trong Gate 3. Ngày quyết định gốc
không được ghi lại. Ngày 2026-09-22, chủ sở hữu xác nhận ba FSM phải là domain
thuần Java và không dùng Spring State Machine.

## Bối cảnh

Payment là service nhạy cảm nhất (xử lý tiền thật): PostgreSQL-first với ACID tối
thượng; ba FSM (Payment Transaction + Refund Transaction + Dispute) với nhiều transition
và edge case (webhook race, timeout, retry, replay/out-of-order từ Stripe retry
3–5 lần); verify `Stripe-Signature` HMAC-SHA256; reconciliation hourly với Stripe
API; concurrency bằng optimistic locking + PostgreSQL lease/fencing. Cần chọn ngôn
ngữ/framework giữa Java (Spring Boot) và Golang (Gin).

## Quyết định

Chọn **Java (Spring Boot)** với tech stack:

| Component | Công nghệ |
|---|---|
| Framework | Spring Boot 3.x + Spring Web |
| FSM | Ba FSM thuần Java trong domain, transition table/guard bất biến và exhaustive tests; không phụ thuộc Spring |
| Database | PostgreSQL + Spring Data JPA; `@Transactional` gom transaction + outbox + status history; `@Version` optimistic locking |
| Kafka | Spring Kafka (webhook raw dump, outbox relay, event production) |
| Idempotency/concurrency | PostgreSQL unique constraints, optimistic CAS, durable lease và fencing token; Gate 3 không có Redis dependency |
| Security | Java Crypto (`javax.crypto`) HMAC-SHA256/512; Stripe SDK `Webhook.constructEvent` |
| HTTP Client | Spring WebClient / OkHttp (gọi Stripe API) |
| Scheduling | Spring `@Scheduled` + Spring Retry (Reconciliation, Expiration Scanner, Refund Poller) |
| Build | Maven + JDK 21 |

## Phương án đã cân nhắc

- **Golang (Gin):** phải tự quản lý transaction/integration stack và ba aggregate FSM;
  Stripe có Go SDK nhưng Java SDK mature hơn cho finance domain
  (long-term support, community, examples); manual transaction management cho
  money-handling là rủi ro cao. Không chọn.
- **Java (Spring Boot) + FSM thuần:** transition table giữ domain độc lập framework,
  exhaustive test kiểm mọi cặp state/trigger; `@Transactional` + optimistic CAS ở
  application/persistence boundary; Stripe Java SDK cung cấp sẵn webhook
  verify đúng chuẩn (kèm timestamp window 300s); `@Scheduled` + Spring Retry cho
  cronjob. Được chọn.

## Hệ quả

Tích cực: domain FSM thuần, deterministic và test không cần Spring; transactional integrity dễ đảm bảo, SDK mature, hệ sinh
thái scheduling/retry sẵn có. Tiêu cực: runtime nặng hơn Go, startup/memory cao
hơn, cần JDK 21 + Maven trong build pipeline (repository hiện build Java qua
Docker Maven).

## Ảnh hưởng migration

Gate 3 đã hiện thực cấu trúc package (`com.platform.paymentservice.stripe`,
domain/application/adapter), build Maven, Spring Kafka/JDBC. Runtime không kéo
Redis/Redisson hay Spring State Machine; DB lease/fencing,
optimistic lock và PostgreSQL constraint là authority.

## Xác minh

`pom.xml`, ba domain FSM thuần với exhaustive tests, transaction boundary gom
aggregate + outbox + history/journal/review và toàn bộ scheduler config đã có;
`mvn verify` là bằng chứng executable.
