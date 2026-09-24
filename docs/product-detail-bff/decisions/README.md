# Product Detail BFF — Architecture Decision Records

| ADR | Decision | Status | Date | Quan hệ |
|---|---|---|---|---|
| [ADR-001](./ADR-001-availability-projection-over-live-query.md) | Inventory-owned batch availability read thay vì BFF projection | Accepted | 2026-09-09 | Amended bởi ADR-003 (caller Reserve) |
| [ADR-002](./ADR-002-review-read-composition-without-domain-ownership.md) | Compose Review read, không đưa domain Review vào BFF | Accepted | 2026-09-07 | — |
| [ADR-003](./ADR-003-checkout-preview-order-reserve-boundary.md) | PDP advisory read; Checkout Preview, Order Reserve | Accepted | 2026-09-20 | Amends ADR-001 |
| [ADR-004](./ADR-004-resilience-budget-and-mesh-split.md) | Resilience budget của PDP, phân chia với mesh/gateway, cache response degrade | Accepted | 2026-09-24 | — |
