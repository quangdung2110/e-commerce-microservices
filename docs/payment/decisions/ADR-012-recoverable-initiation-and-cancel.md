# ADR-012 — Recoverable provider initiation and pre-capture cancellation

## Status

Accepted — 2026-09-16. Refines ADR-006 for Stripe side effects and ADR-007/008 for
the Order financial event contract; prior ADRs remain historical decisions.

## Context

Persisting an empty `PENDING` row before `PaymentIntent.create` leaves an ambiguous
provider side effect after a timeout. Separately, using asynchronous `ORDER_CANCELLED`
as the first cancellation command permits a provider capture to race Order's resource
rollback. Neither Redis locks nor Kafka partition ordering can prove the financial
outcome of either race.

## Decision

Payment persists a `ProviderOperation` with canonical fingerprint and a stable provider
idempotency key before every Stripe create, pre-capture cancel, and refund operation.
The same key is replayed after an uncertain response; reconciliation locates the
provider object through immutable internal metadata when replay alone cannot bind it.
`INITIATING` is a recoverable state, not an expirable empty payment session.

Order invokes synchronous `POST /v1/payments/{transactionId}/cancel` before it emits
the factual `ORDER_CANCELLED` event or releases pre-payment resources. Payment returns
either a durable non-capture outcome or `ALREADY_SUCCEEDED`; it never cancels a
captured payment by assumption. Unknown outcomes remain `CANCEL_PENDING` and are
recovered using the same operation identity. `ORDER_CANCELLED` remains a consumer
reconciliation notification and never authorizes automatic refund.

Payment event schema version 2 includes `currency`, the immutable captured payment
transaction identity on refund facts, and monotonic `financial_state_version`, so Order can
durably validate a financial fact rather than relying only on order partition order.

## Consequences

This adds a provider-operation table, recovery queue, and contract-test obligation.
It removes the empty-PENDING hole and makes cancel/capture resolution explicit. When
the provider proves capture, Order must use its financial recovery/manual lane rather
than compensation that assumes no money moved.

## Verification

Test provider-create timeout before/after provider acceptance, replay with the same
key, reconciliation identity binding, cancel-versus-success races, and duplicate or
out-of-order version-2 events. Every outcome must leave a durable audit/outbox trail;
only a proven non-capture result may allow Order to final-cancel.
