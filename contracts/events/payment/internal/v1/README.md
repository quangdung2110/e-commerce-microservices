# Payment internal Stripe webhook buffer

This contract is internal to Payment Service. `payment-api` sends one structured
CloudEvent to `t.payment.webhooks.raw`; `payment-worker` receives it in consumer
group `payment-webhook-processor`.

The HTTP body is base64 encoded so decoding reproduces the exact bytes required by
Stripe signature verification. `candidate_event_id` and the subject are routing
hints only. The verified Stripe event ID stored in `webhook_logs` is the sole
business deduplication identity.

The message `x-example-file` points to
`contracts/examples/events/payment-stripe-webhook-received.v1.json`; its digest is
the SHA-256 of the decoded raw bytes, so the fixture also exercises byte fidelity.

The topic contains confidential raw provider data and is not an integration
contract for other services.
