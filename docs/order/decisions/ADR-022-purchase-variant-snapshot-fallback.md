---
doc_id: order-adr-022
service: order
status: accepted
date: 2026-10-13
evidence:
  - java/services/checkout-service/src/main/java/com/ecommerce/checkout/service/OrderPayloadFactory.java
  - java/services/order-service/src/main/java/com/ecommerce/order/domain/VariantSnapshot.java
  - java/services/order-service/src/main/java/com/ecommerce/order/service/OrderConvergence.java
  - contracts/events/order/v1/schemas/order-completed.schema.json
---

# ADR-022: Purchase variant snapshot fallback

Status: accepted — 2026-10-13. Amends ADR-020 item 2.

## Context

Checkout saved SKU attributes but omitted `variant_name` because Product had no
separate display label. Consequently completed orders granted review entitlements with
NULL variant labels, making distinct SKUs appear identical on Buyer review cards.

## Decision

Checkout derives the label from the accepted purchase attributes: skip blank values,
sort by key, join `key: value` with ` / ` and cap at 255 UTF-16 units without splitting
a surrogate pair. No usable attributes means NULL. Persist this label in the immutable
Order line snapshot. Explicit nonblank labels retain precedence.

Order applies the same fallback at line creation for older Checkout producers and at
completion for previously stored lines missing a label. The completed fact uses only
stored purchase metadata; it never reads current Product data. Existing events and
claimed entitlements are not regenerated. Review uses the completed-event snapshot.

Operator repair of already-missing Review labels uses completed Order purchase
snapshots, matches buyer/order/product/SKU and updates NULL display labels only. It
does not grant rights, alter expiry, reviews/ratings, erase markers or publish events.
See the dry-run/apply procedure in [Review operations](../../review/operations.md).

## Verification

Checkout `SubmitApiTest` validates the actual HTTP payload, label and unchanged hashes.
Order `CheckoutFlowIT` checks persistence, dispatch/delivery, completion and contract
validation for a line whose legacy label is missing. `VariantSnapshotTest` covers label
precedence, stable ordering, empty attributes and the length limit. Runtime QA repair
and distinct Buyer cards are documented in [the fix report](../../frontend/e2e-reviews-fix-report-2026-10-13.md).
