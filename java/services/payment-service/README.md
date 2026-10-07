# Payment Service — Gate 3 runtime

Spring Boot/Java 21 runtime nằm trong package `com.platform.paymentservice`.
Gate 3 đã hiện thực initiate/cancel/refund/client-data, persistence/idempotency,
Stripe adapter, encrypted webhook inbox, Kafka processor, recovery/reconciliation,
retention và Finance Ops API. [Implementation gates](../../../docs/payment/implementation-gates.md)
ghi bằng chứng offline; Stripe Sandbox E2E là gate riêng, chưa được coi là PASS.

## Local checkout

Theo [checkout runbook](../../../docs/frontend/checkout-local-runbook.md), từ gốc repo:

```powershell
docker compose --env-file .env.local --profile payment up -d --build payment-service
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services payment
```

Chuẩn bị khóa Sandbox theo [development](../../../docs/payment/development.md).
Root `.env.local` đã ignore; Compose cần `--env-file`. Payment là profile opt-in
`payment`, host 8089, internal `payment-service:8080`. Webhook qua Gateway:
`http://localhost:8088/v1/payments/webhook/stripe`. Command API dùng service identity;
frontend đọc payment credentials qua Order.

Flyway áp dụng migration trên DB `payment_service`. Debezium relay
`public.outbox_events` tới `t.payment.events.v2`, key từ `partition_key`. Đăng ký
connector trước đơn mới (`snapshot.mode=no_data`). Webhook 2xx/container healthy
chưa chứng minh Order PAID.

## Validation

```powershell
pwsh -NoProfile -File java/services/payment-service/scripts/validate-payment-gate2.ps1
```

Validator foundation/contract này không thay thế test runtime. Lệnh Maven/integration,
cấu hình và giới hạn bằng chứng nằm trong development/implementation gates. Stripe
SDK/API đã pin theo ADR-021; giữ version khi cấu hình webhook.
