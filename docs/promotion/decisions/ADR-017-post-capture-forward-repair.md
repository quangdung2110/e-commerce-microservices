# ADR-017 — Post-capture promotion failures use forward repair

## Status

Accepted — 2026-09-16. Refines the active-use interpretation of ADR-013 and
ADR-016; their durable receipt/finalization mechanics remain valid.

## Context

`CANCEL_CONFIRMED` changes a redeemed wallet/budget back to unredeemed. That can be
valid only for historical cleanup that demonstrably never crossed a financial
watermark. In the active checkout design, Promotion CONFIRM follows Payment success.
Calling technical cancellation when a later post-payment step fails creates an
impossible business history: money was captured but the voucher looks unused, and a
retry cannot safely re-confirm the original `REDEEM_FINAL` business operation.

## Decision

For active Order checkout and late-payment recovery, `CONFIRM` is final once Payment
has a `PAYMENT_SUCCESS`/`ALREADY_SUCCEEDED` fact. Any subsequent technical failure
uses retry/forward repair with the same durable receipt or Order financial
reconciliation. It never uses `CANCEL_CONFIRMED`, whether the failure is Inventory,
workflow, transport, or timeout.

The technical-cancellation endpoint and receipt remain deprecated legacy/migration
cleanup only. It requires auditable proof that its historical flow never crossed the
financial watermark. It is prohibited for active checkout, late-payment recovery and
customer/admin refund (which already leave wallet/budget `USED`).

## Consequences

Promotion preserves a truthful redeemed state after capture. Order must retain a
financial repair/manual lane instead of treating LIFO compensation as universally
valid. This deliberately may require operational resolution where a pre-confirm
reservation cannot be recreated after a late payment; it avoids issuing a voucher
for a financially captured order.

## Verification

Provider/consumer contract tests must reject an active post-capture request to the
deprecated endpoint. Failure-injection tests must prove the same CONFIRM receipt is
replayed/forward-repaired without a second wallet/budget transition, and refund flows
make no Promotion mutation.
