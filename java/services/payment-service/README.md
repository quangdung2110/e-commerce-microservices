# Payment Service — Gate 2 implementation foundation

Thư mục này hiện chỉ chứa persistence/CDC foundation, chưa phải runtime Spring Boot.
Nguồn chuẩn nghiệp vụ vẫn là `docs/payment/`; contract chuẩn nằm ở `contracts/`.

## Artifacts

- `src/main/resources/db/migration/V1__payment_baseline.sql`: 12 bảng, constraints,
  indexes, refund guard, append-only audit, double-entry balance trigger và procedure
  `post_financial_journal`.
- `src/main/resources/kafka/debezium-connector-payment-outbox.json`: CDC duy nhất từ
  `outbox_events` sang `t.payment.events.v2`, key bằng `order_id`, không tombstone.
- `scripts/validate-payment-gate2.ps1`: validator không cần tải dependency.

## Validate

```powershell
pwsh -NoProfile -File java/services/payment-service/scripts/validate-payment-gate2.ps1
```

Migration yêu cầu PostgreSQL 15+. Gate tiếp theo phải chạy migration thật trên
PostgreSQL và integration-test các constraint trước khi viết provider adapter.
