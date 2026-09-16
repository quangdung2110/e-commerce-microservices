---
doc_id: delivery-overview
service: delivery
scope: target
status: proposed
owners:
  - Delivery Service team
evidence: []
verified_against_commit: null
---

# Delivery Service — Verified delivery proof boundary

Delivery owns the customer-delivery proof that is distinct from Inventory's physical
dispatch fact. It emits only
[`com.ecommerce.delivery.order-line.delivered.v1`](../../contracts/events/delivery/v1/asyncapi.yaml)
after verifying a delivery unit for the immutable `order_id`, `order_line_id` and
`warehouse_id` allocation.

The event carries a unique `delivery_proof_id`, SHA-256 of its immutable verified-proof
record (never raw proof/PII), `delivered_at` and monotonic aggregate revision. It is
at-least-once; Delivery never mutates Order state. Order validates the mapping and proof
identity/digest, may transition only that line `DISPATCHED -> DELIVERED`, and completes the
master only after every line has a valid proof.

This target contract is `proposed`: no Delivery implementation, deployment or provider test
was found in this repository. Inventory physical dispatch cannot publish or substitute this
proof.
