# Checkout Service

Backend for Frontend of the checkout page: an immutable server-owned quotation, the voucher tray for
that quotation, an exactly-once submit to Order, and recovery of an unknown result. The only state it owns
is the short-lived workflow state in its own Redis. Design, contracts and runbooks live in
[docs/checkout](../../../docs/checkout/README.md); configuration is in
[operations.md](../../../docs/checkout/operations.md#cấu-hình).

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/checkout/preview` | Create the user's current quotation (idempotent by `Idempotency-Key`) |
| `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` | Voucher tray evaluated on that quotation |
| `POST /api/v1/checkout/submit` | Claim the token, revalidate, call Order once |
| `GET /api/v1/checkout/submissions/{checkout_token}` | Read or recover the result |

| Package | Role |
|---|---|
| `api` | Controller, request context filter (deadline, request ID, `Cache-Control`, access line), strict request parser, problem+json |
| `service` | `PreviewService` (waves), `SubmissionService` (claim, detached work, recovery), `Revalidator`, `VoucherOptionsService`, Order payload and outcome mapping |
| `store` | `SessionStore`: every state transition is one Lua script (`src/main/resources/lua`) |
| `client` | One client per dependency (Cart, User, Product gRPC, Inventory, Promotion, Order), tolerant readers with contract checks |
| `resilience` | `DependencyGuard`: deadline budget, timeout, circuit breaker and bulkhead per dependency, outcome classification |
| `observability` | `CheckoutMetrics` (counters of operations.md) and `Spans` (use case, dependency and Redis spans, no metrics) |
| `crypto` | HMAC lookup refs, envelope encryption, key rings, CSPRNG ids |
| `domain` | Snapshot, state, RFC 8785 hashes of the Order recipe |
| `problem` | The `CheckoutProblem` catalog |

## Build and test

There is no JDK on the development machine; build in the Maven container. Tests start Redis with
Testcontainers (Lua runs for real), use WireMock for the five HTTP providers and an in-process gRPC server
for Product, and read `contracts/` and `docs/`, so mount the repository root and the Docker socket.

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "//c/Users/ASUS/MyProjects/e-commerce-microservices://repo" -v checkout-mvn-repo://root/.m2 \
  --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w //repo/java/services/checkout-service maven:3.9-eclipse-temurin-21 \
  mvn -B -Dos.detected.classifier=linux-x86_64 test
```

`proto/` is a copy of the canonical Product proto so the Docker build context is this directory;
`ProtoCopyTest` fails the build when it drifts from `contracts/`. Run locally with
`docker compose up -d checkout-service` (it starts its own `checkout-redis`, `noeviction`).
