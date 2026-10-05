# Payment financial events v2

`asyncapi.yaml` is the provider-owned contract for `t.payment.events.v2`.
Every channel message has one JSON Schema in `schemas/`; the uppercase name is a
business alias, while routing and dispatch use the exact namespaced CloudEvents
`type` declared by that schema.

Every message also declares `x-example-file` pointing to a validation fixture in
`contracts/examples/events/`. The fixture is a complete Kafka value, not only the
`data` object, and MUST validate against the message payload schema.

The Kafka key MUST equal `data.order_id`. Delivery is at-least-once. Consumers
deduplicate by `(source, id)`, persist their inbox record with the local mutation,
and compare `data.financial_state_version` only within the same
`payment_transaction_id`.

The canonical Kafka value is the complete CloudEvents structured JSON envelope.
Kafka headers may duplicate identifiers for observability, but they are not the
contract authority. `client_secret`, Stripe object IDs, payment-method data and
raw provider payloads are forbidden from this topic.
Unknown optional fields remain allowed for additive evolution; consumers ignore
them. The explicitly forbidden sensitive/provider fields remain invalid even when
added by mistake.

The topic also carries the Payment-owned dispute lifecycle: opened, provider funds
withdrawn/reinstated, won and lost. A dispute is orthogonal to PaymentTransaction
status; its facts still advance `financial_state_version` on the captured payment.

See `docs/payment/interfaces.md` for business semantics and
`contracts/events/payment/internal/v1/asyncapi.yaml` for the internal verified
Stripe webhook buffer.
