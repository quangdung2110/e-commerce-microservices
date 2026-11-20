# ADR-024 — Certified CREATE non-acceptance without a financial transition

Status: Accepted — 2026-10-15. Refines ADR-012/014/017 for provider validation before an intent exists.

## Context

Checkout F08-01 produced final=1 VND. Stripe returned 400 `amount_too_small`; Payment
froze a 503 PROVIDER_REQUEST_REJECTED and opened review. Order correctly classified
5xx as UNKNOWN, so `untilKnown(initiate)` could not reach cancellation or hold-window
handling. Treating all provider rejections, missing transaction IDs or 5xx as non-capture
would violate financial safety, particularly after transport ambiguity/idempotency errors.

## Decision

Payment HTTP 1.1.0 adds 422 PROVIDER_CREATE_NOT_ACCEPTED, with explicit
`no_payment_created=true`, transaction_id, provider_operation_id, order_id and attempt.
This is a proof that CREATE was not accepted, not a terminal Payment financial fact.

The allowlist is deliberately narrow: HTTP 400 `amount_too_small` on a first CREATE
dispatch. Before dispatch the operation must be REQUESTED, attempt_count=0,
first_dispatched_at=null, provider_object_id=null. Persisting the proof independently
checks the fenced CREATE is still REQUESTED with exactly one recorded dispatch, has no
provider object, and Payment is INITIATING with no provider ID and financial version 0.
Other 4xx (including idempotency_error), transport ambiguity, recovery/re-dispatch,
identity binding problems and 5xx keep existing UNKNOWN/review behavior.
CREATE disables hidden Stripe SDK network retries; every retry passes through durable
operation dispatch accounting with the same key. Other operations retain configured SDK retries.

The operation becomes PROVIDER_REJECTED and the command atomically freezes the full
422 proof. Replay returns the same proof without dispatch. No review case is needed for
this certified path. The Payment INITIATING row is an inert audit of a CREATE that never
produced an intent; no financial version, status-history transition or outbox fact is invented.
Recovery scanners skip terminal operations. There is no new Payment status or DB migration.

Order validates the proof against the frozen request and internal identity formats.
Invalid/missing/mismatched proof is UNKNOWN. Valid proof is a durable rejection and uses
the existing PAYMENT_INITIATION_REJECTED → pre-payment cancellation path. No attempt is
bound and no payment form is served. Inventory/Promotion cleanup completes before final
CANCELLED. A later purchase uses a new Checkout token, not a retry attempt on this row.

## Legacy and rollout

Deploy the consumer validation before/with the Payment producer. Unknown consumers
must not interpret arbitrary 422 responses as absence proof. Existing 503 idempotency
receipts/review cases, including QA Order 770072, remain immutable under normal replay;
this fix does not silently rewrite historical financial evidence or bypass maker-checker.
Resolve such cases through Finance's supported evidence/correction lane. Do not reset
Temporal history, replace command keys, delete reservations or update financial rows manually.

## Verification

Test Stripe first-dispatch classification, replay without a second provider call, DB lease
and proof fences, malformed proof, earlier UNKNOWN/re-dispatch, ordinary rejection, recovery
worker first dispatch, consumer order/attempt mismatches, and Order compensation with both
holds. Real Sandbox 1 VND must converge to Checkout rejection and Order CANCELLED with
no captured money, no client secret, no remaining reservation and no new review case.
